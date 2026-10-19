package com.nebula.iot.bootstrap.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.message.*;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

/** Resource-authorized external notifications; deliberately no arbitrary internal-topic API. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@Component
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class SubscriptionGateway extends TextWebSocketHandler {
    public record Resource(String tenantId, String deviceId) { }
    public record Ticket(String ticket, Instant expiresAt) { }
    record Grant(String actor, HttpSession session, String sessionId, List<Resource> devices, Instant expiresAt) { }
    private final CatalogService catalog;
    private final ObjectMapper json;
    private final Map<String, Grant> tickets = new HashMap<>();
    private final ConcurrentMap<String, Client> clients = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1024), Thread.ofPlatform().daemon(true).name("subscription-send-", 0).factory());
    private final ThreadPoolExecutor closers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2048), Thread.ofPlatform().daemon(true).name("subscription-close-", 0).factory());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("subscription-heartbeat").factory());
    private final Subscription subscription;

    public SubscriptionGateway(CatalogService catalog, ObjectMapper json, EventBus bus) {
        this.catalog = catalog; this.json = json;
        subscription = bus.subscribe(SubscriptionSpec.broadcast("realtime-v1", "external/device/*"), event -> {
            // No socket I/O or database queries on the event-bus callback.
            clients.values().forEach(client -> {
                if (client.grant.devices().contains(new Resource(platformTenant(event.tenantId()), event.deviceId())))
                    client.offer(event.tenantId()+":"+event.deviceId()+":"+event.topic(), Map.of(
                            "protocol", "nebula.subscription.v1", "type", "invalidate", "eventId", event.eventId(),
                            "tenantId", event.tenantId(), "deviceId", event.deviceId(),
                            "kind", event.topic().endsWith("/shadow") ? "shadow" : "presence", "occurredAt", event.occurredAt()));
            });
            return CompletableFuture.completedFuture(null);
        });
        timer.scheduleAtFixedRate(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }
    static String platformTenant(String tenant) { return "platform".equals(tenant) ? null : tenant; }
    private Resource authorize(String actor, Resource requested) {
        if (requested == null || requested.deviceId() == null || requested.deviceId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device required");
        // Resolve the actual resource: platform directory reads can include tenant devices.
        var device = catalog.device(actor, requested.tenantId(), requested.deviceId());
        return new Resource(device.tenantId(), device.id());
    }
    public synchronized Ticket issue(String actor, HttpSession session, List<Resource> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subscribe to 1 to 100 devices");
        tickets.values().removeIf(g -> g.expiresAt().isBefore(Instant.now()));
        if (tickets.size() + clients.size() >= 2000 || tickets.values().stream().filter(g -> g.actor().equals(actor)).count()
                + clients.values().stream().filter(c -> c.grant.actor().equals(actor)).count() >= 10)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Subscription limit reached");
        var devices = requested.stream().map(r -> authorize(actor, r)).distinct().toList();
        String token = UUID.randomUUID().toString(); var expires = Instant.now().plusSeconds(30);
        tickets.put(token, new Grant(actor, session, session.getId(), devices, expires));
        return new Ticket(token, expires);
    }
    synchronized Grant consume(String token, Principal actor, HttpSession session) {
        if (token == null) return null;
        var grant = tickets.remove(token);
        if (grant == null || actor == null || session == null || !grant.actor().equals(actor.getName())
                || !grant.sessionId().equals(session.getId()) || !grant.expiresAt().isAfter(Instant.now())) return null;
        validate(grant);
        return grant;
    }
    private void validate(Grant grant) {
        // Invalidated/logged-out sessions throw IllegalStateException. Heartbeats do not extend idle expiry.
        if (!grant.sessionId().equals(grant.session().getId())) throw new IllegalStateException("Session changed");
        long idle = System.currentTimeMillis() - grant.session().getLastAccessedTime();
        int timeout = grant.session().getMaxInactiveInterval();
        if (timeout > 0 && idle >= timeout * 1000L) throw new IllegalStateException("Session expired");
        grant.devices().forEach(r -> authorize(grant.actor(), r));
    }
    @Override public synchronized void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        var grant = (Grant) socket.getAttributes().get("grant");
        if (grant == null || clients.size() >= 2000 || clients.values().stream().filter(c -> c.grant.actor().equals(grant.actor())).count() >= 10) {
            socket.close(CloseStatus.POLICY_VIOLATION); return;
        }
        socket.setTextMessageSizeLimit(16);
        var client = new Client(socket, grant);
        clients.put(socket.getId(), client);
        client.offer("ready", Map.of("protocol", "nebula.subscription.v1", "type", "ready", "resyncRequired", true));
    }
    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        if ("pong".equals(message.getPayload())) {
            var client = clients.get(socket.getId()); if (client != null) client.lastPong = System.currentTimeMillis();
        } else closeClient(socket.getId(), CloseStatus.BAD_DATA);
    }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) { clients.remove(socket.getId()); }
    @Override public void handleTransportError(WebSocketSession socket, Throwable exception) { closeClient(socket.getId(), CloseStatus.SERVER_ERROR); }
    private void heartbeat() {
        clients.values().forEach(client -> {
            if (System.currentTimeMillis() - client.lastPong > 45000) { closeClient(client.socket.getId(), CloseStatus.SESSION_NOT_RELIABLE); return; }
            if (((ConcurrentWebSocketSessionDecorator) client.socket).getTimeSinceSendStarted() > 5000) { closeClient(client.socket.getId(), CloseStatus.SESSION_NOT_RELIABLE); return; }
            client.offer("heartbeat", Map.of("protocol", "nebula.subscription.v1", "type", "ping"));
        });
        synchronized (this) { tickets.values().removeIf(g -> !g.expiresAt().isAfter(Instant.now())); }
    }
    private void closeClient(String id, CloseStatus reason) {
        var client = clients.remove(id);
        if (client != null) {
            try { closers.execute(() -> { try { client.socket.close(reason); } catch (Exception ignored) { } }); }
            catch (RejectedExecutionException shuttingDown) { /* Shutdown has already closed admission. */ }
        }
    }
    private final class Client {
        final WebSocketSession socket;
        final Grant grant;
        final LinkedHashMap<String, Object> pending = new LinkedHashMap<>();
        boolean sending;
        volatile long lastPong = System.currentTimeMillis();
        Client(WebSocketSession socket, Grant grant) {
            this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 65536); this.grant = grant;
        }
        synchronized void offer(String key, Object value) {
            if (!clients.containsKey(socket.getId())) return;
            pending.put(key, value); // Coalesce only invalidations, never business event history.
            if (pending.size() > 202) { closeClient(socket.getId(), CloseStatus.SESSION_NOT_RELIABLE); return; }
            if (!sending) {
                sending = true;
                try { senders.execute(this::flush); }
                catch (RejectedExecutionException full) { sending = false; closeClient(socket.getId(), CloseStatus.SESSION_NOT_RELIABLE); }
            }
        }
        void flush() {
            try {
                while (socket.isOpen() && clients.containsKey(socket.getId())) {
                    Object message;
                    synchronized (this) {
                        if (pending.isEmpty()) { sending = false; return; }
                        var entry = pending.entrySet().iterator(); message = entry.next().getValue(); entry.remove();
                    }
                    validate(grant); // Revalidate account, permission, tenant membership and resource before every send.
                    socket.sendMessage(new TextMessage(json.writeValueAsString(message)));
                }
            } catch (Exception deniedOrBroken) { closeClient(socket.getId(), CloseStatus.POLICY_VIOLATION); }
            synchronized (this) { sending = false; }
        }
    }
    @PreDestroy public void shutdown() {
        subscription.close(); timer.shutdownNow();
        clients.keySet().forEach(id -> closeClient(id, CloseStatus.GOING_AWAY));
        senders.shutdownNow(); closers.shutdown(); synchronized (this) { tickets.clear(); }
    }
}

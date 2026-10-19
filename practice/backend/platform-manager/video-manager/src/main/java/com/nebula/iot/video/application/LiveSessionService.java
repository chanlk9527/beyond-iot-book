package com.nebula.iot.video.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.media.api.MediaProvider;
import com.nebula.iot.media.api.MediaProvider.*;
import com.nebula.iot.video.api.LiveAccessPolicy;
import com.nebula.iot.video.api.LiveAccessPolicy.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Single-node, bounded sessions. Each request rechecks the authoritative business policy. */
public final class LiveSessionService implements AutoCloseable {
    public record Channel(String scope, String deviceId, String channelId, String resource) {}
    public record View(String sessionId, String protocol, Instant expiresAt, List<IceServer> iceServers) {
        @Override public String toString() { return "LiveSession[" + sessionId + "]"; }
    }
    private record Session(Identity owner, String tenant, String device, String channel,
                           Target target, Connection connection, View view) {}
    private final LiveAccessPolicy policy;
    private final MediaProvider provider;
    private final List<Channel> channels;
    private final Clock clock;
    private final Duration ttl;
    private final int maximum;
    private final ObjectMapper json;
    private final Map<String, Session> sessions = new HashMap<>();
    public LiveSessionService(LiveAccessPolicy policy, MediaProvider provider, List<Channel> channels,
                              Clock clock, Duration ttl, int maximum, ObjectMapper json) {
        if (ttl.compareTo(Duration.ofSeconds(10)) < 0 || ttl.compareTo(Duration.ofMinutes(5)) > 0 || maximum < 1 || maximum > 1000)
            throw new IllegalArgumentException("Session TTL must be 10s..5m and maximum 1..1000");
        this.policy = policy; this.provider = provider; this.channels = List.copyOf(channels);
        this.clock = clock; this.ttl = ttl; this.maximum = maximum; this.json = json;
        var keys = new HashSet<List<String>>(); var resources = new HashSet<String>();
        for (var c : channels) {
            if (c.scope() == null || c.scope().isBlank() || c.deviceId() == null || c.deviceId().isBlank()
                    || c.channelId() == null || !c.channelId().matches("[A-Za-z0-9_-]{1,64}") || c.resource() == null || c.resource().isBlank()
                    || !keys.add(List.of(c.scope(), c.deviceId(), c.channelId())) || !resources.add(c.resource()))
                throw new IllegalArgumentException("Invalid or duplicate channel binding");
        }
    }
    public synchronized View create(Identity actor, String tenant, String device, String channel) {
        Target target = policy.requireWatch(actor, tenant, device, channel);
        var binding = channels.stream().filter(c -> c.scope().equals(target.scope()) && c.deviceId().equals(target.deviceId())
                && c.channelId().equals(target.channelId())).findFirst().orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "Live channel not configured"));
        sweep();
        if (sessions.size() >= maximum) throw problem(HttpStatus.TOO_MANY_REQUESTS, "Live session capacity reached");
        String id = UUID.randomUUID().toString();
        Connection connection;
        try { connection = provider.open(binding.resource(), id); }
        catch (RuntimeException e) { throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Media provider unavailable"); }
        try {
            // Authorization may have been revoked while the remote connection was being established.
            if (!target.equals(policy.requireWatch(actor, tenant, device, channel))) throw problem(HttpStatus.FORBIDDEN, "Live authorization changed");
            Instant expiry = clock.instant().plus(ttl);
            if (connection.expiresAt().isBefore(expiry)) expiry = connection.expiresAt();
            if (!connection.isOpen() || !expiry.isAfter(clock.instant())) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Media connection unavailable");
            View view = new View(id, "webrtc", expiry, List.copyOf(connection.iceServers()));
            sessions.put(id, new Session(actor, tenant, device, channel, target, connection, view));
            return view;
        } catch (RuntimeException e) { connection.close(); throw e; }
    }
    public synchronized void send(Identity actor, String id, Signal signal) {
        var session = require(actor, id);
        validate(signal);
        try { session.connection().send(signal); }
        catch (RuntimeException e) { remove(id); throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Media signaling unavailable"); }
    }
    public synchronized void send(Identity actor, String id, String tenant, String device, String channel, Signal signal) {
        requireTarget(actor, id, tenant, device, channel);
        send(actor, id, signal);
    }
    public synchronized List<Signal> receive(Identity actor, String id) {
        var session = require(actor, id);
        try { return session.connection().drain(); }
        catch (RuntimeException e) { remove(id); throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Media signaling unavailable"); }
    }
    public synchronized List<Signal> receive(Identity actor, String id, String tenant, String device, String channel) {
        requireTarget(actor, id, tenant, device, channel);
        return receive(actor, id);
    }
    public synchronized void delete(Identity actor, String id) {
        var session = sessions.get(id);
        if (session == null) return;
        if (!session.owner().equals(actor)) throw problem(HttpStatus.NOT_FOUND, "Live session not found");
        // Owner may release their resources even after business permission has been revoked.
        remove(id);
    }
    public synchronized void delete(Identity actor, String id, String tenant, String device, String channel) {
        requireTarget(actor, id, tenant, device, channel);
        delete(actor, id);
    }
    private void requireTarget(Identity actor, String id, String tenant, String device, String channel) {
        var session = require(actor, id);
        if (!Objects.equals(session.tenant(), tenant) || !Objects.equals(session.device(), device) || !Objects.equals(session.channel(), channel))
            throw problem(HttpStatus.NOT_FOUND, "Live session not found");
    }
    private Session require(Identity actor, String id) {
        var session = sessions.get(id);
        if (session == null || !session.owner().equals(actor)) throw problem(HttpStatus.NOT_FOUND, "Live session not found");
        if (!session.view().expiresAt().isAfter(clock.instant()) || !session.connection().isOpen()) {
            remove(id); throw problem(HttpStatus.GONE, "Live session expired or closed");
        }
        try {
            if (!session.target().equals(policy.requireWatch(actor, session.tenant(), session.device(), session.channel())))
                throw problem(HttpStatus.FORBIDDEN, "Live authorization changed");
        } catch (RuntimeException e) { remove(id); throw e; }
        return session;
    }
    private void validate(Signal signal) {
        if (signal == null || signal.type() == null || signal.payload() == null || signal.payload().length() > 16000)
            throw problem(HttpStatus.BAD_REQUEST, "Invalid signal (maximum 16000 characters)");
        try {
            var payload = json.readTree(signal.payload());
            if (payload == null || !payload.isObject()) throw new IllegalArgumentException();
            switch (signal.type()) {
                case OFFER -> {
                    if (!"offer".equals(payload.path("type").asText()) || !payload.path("sdp").isTextual()) throw new IllegalArgumentException();
                    requireReceiveOnly(payload.path("sdp").asText());
                }
                case ICE -> {
                    if (!payload.path("candidate").isTextual() || payload.path("candidate").asText().length() > 4096)
                        throw new IllegalArgumentException();
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (Exception e) { throw problem(HttpStatus.BAD_REQUEST, "Viewer requires receive-only SDP offer or ICE candidate"); }
    }
    /** Directions are inherited from the session section; missing direction defaults to sendrecv. */
    static void requireReceiveOnly(String sdp) {
        String inherited = "sendrecv", direction = null;
        boolean directionSeen = false;
        boolean media = false;
        int count = 0;
        for (String raw : sdp.split("[\\r\\n]+")) {
            String line = raw.strip();
            if (line.startsWith("m=")) {
                if (media && !Set.of("recvonly", "inactive").contains(direction)) throw new IllegalArgumentException();
                if (!line.startsWith("m=audio ") && !line.startsWith("m=video ")) throw new IllegalArgumentException();
                media = true; count++; direction = inherited; directionSeen = false;
            } else if (Set.of("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive").contains(line)) {
                if (directionSeen) throw new IllegalArgumentException();
                directionSeen = true;
                if (media) direction = line.substring(2); else inherited = line.substring(2);
            }
        }
        if (count == 0 || !Set.of("recvonly", "inactive").contains(direction)) throw new IllegalArgumentException();
    }
    public synchronized void sweep() {
        for (var id : List.copyOf(sessions.keySet())) {
            var s = sessions.get(id);
            if (!s.view().expiresAt().isAfter(clock.instant()) || !s.connection().isOpen()) { remove(id); continue; }
            try {
                if (!s.target().equals(policy.requireWatch(s.owner(), s.tenant(), s.device(), s.channel()))) remove(id);
            } catch (RuntimeException denied) { remove(id); }
        }
    }
    private void remove(String id) { var session = sessions.remove(id); if (session != null) session.connection().close(); }
    @Override public synchronized void close() { List.copyOf(sessions.keySet()).forEach(this::remove); }
    private static ResponseStatusException problem(HttpStatus status, String detail) { return new ResponseStatusException(status, detail); }
}

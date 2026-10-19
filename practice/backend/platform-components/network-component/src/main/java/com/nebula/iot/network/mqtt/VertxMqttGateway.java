package com.nebula.iot.network.mqtt;

import com.nebula.iot.core.auth.*;
import com.nebula.iot.core.command.*;
import com.nebula.iot.core.device.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.protocol.*;
import com.nebula.iot.gateway.*;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.*;
import io.vertx.mqtt.messages.*;
import io.vertx.mqtt.messages.codes.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static io.netty.handler.codec.mqtt.MqttProperties.MqttPropertyType.*;

/** MQTT 5设备接入服务端：按可信设备会话定向投递，不建立客户端订阅或设备间转发。 */
public final class VertxMqttGateway implements DeviceAccessGateway {

    private static final System.Logger LOG = System.getLogger(VertxMqttGateway.class.getName());


    private final Vertx vertx;
    private final MqttGatewayOptions options;
    private final DeviceAuthenticator authenticator;
    private final DeviceAccessResolver resolver;
    private final DeviceMessageCodec codec;
    private final DeviceMessageHandler handler;
    private final Clock clock;
    private final Map<DeviceKey, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Session> clientIds = new ConcurrentHashMap<>();
    private final Set<MqttEndpoint> connections = ConcurrentHashMap.newKeySet();
    private final AtomicLong acceptedMessages = new AtomicLong();
    private final AtomicLong rejectedMessages = new AtomicLong();
    private volatile java.util.function.BiConsumer<DeviceKey, Boolean> connectionObserver = (key, online) -> { };
    /** Observer must be non-blocking; installed by composition before starting the gateway. */
    public void setConnectionObserver(java.util.function.BiConsumer<DeviceKey, Boolean> observer) {
        connectionObserver = Objects.requireNonNull(observer);
    }
    private void connectionChanged(DeviceKey key, boolean online) {
        try { connectionObserver.accept(key, online); }
        catch (RuntimeException error) { System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "Connection observer failed", error); }
    }
    private volatile SessionOwnership ownership;
    private volatile java.util.function.BooleanSupplier admissionGuard = () -> true;
    private volatile java.util.function.BooleanSupplier serviceGuard = () -> true;
    private volatile boolean acceptingConnections = true;
    private long ownershipTimer = -1;
    /** Installed before start. Database operations must never run on an event loop. */
    public synchronized void configureOwnership(SessionOwnership ownership,
            java.util.function.BooleanSupplier admission, java.util.function.BooleanSupplier service) {
        if (running) throw new IllegalStateException("Ownership must be configured before starting");
        this.ownership = Objects.requireNonNull(ownership);
        this.admissionGuard = Objects.requireNonNull(admission);
        this.serviceGuard = Objects.requireNonNull(service);
    }
    public void pauseAdmissions() { acceptingConnections = false; }
    public void resumeAdmissions() { acceptingConnections = true; }
    public boolean acceptsConnections() { return running && acceptingConnections && admissionGuard.getAsBoolean(); }
    public CompletionStage<Integer> disconnectBatch(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive batch size required");
        var selected = sessions.values().stream().filter(session -> !session.closed).limit(limit).toList();
        var actions = selected.stream().map(session -> {
            var done = new CompletableFuture<Void>();
            session.context.runOnContext(ignored -> { disconnect(session, MqttDisconnectReasonCode.SERVER_SHUTTING_DOWN); done.complete(null); });
            return done;
        }).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(actions).thenApply(ignored -> selected.size());
    }
    private volatile boolean running;
    private volatile MqttServer server;
    private volatile ExecutorService workers;
    private CompletableFuture<Void> starting;
    private CompletableFuture<Void> stopping;

    public VertxMqttGateway(Vertx vertx, MqttGatewayOptions options, DeviceAuthenticator authenticator,
                           DeviceAccessResolver resolver, DeviceMessageCodec codec, DeviceMessageHandler handler) {
        this(vertx, options, authenticator, resolver, codec, handler, Clock.systemUTC());
    }

    public VertxMqttGateway(Vertx vertx, MqttGatewayOptions options, DeviceAuthenticator authenticator,
                           DeviceAccessResolver resolver, DeviceMessageCodec codec, DeviceMessageHandler handler, Clock clock) {
        this.vertx = Objects.requireNonNull(vertx);
        this.options = Objects.requireNonNull(options);
        this.authenticator = Objects.requireNonNull(authenticator);
        this.resolver = Objects.requireNonNull(resolver);
        this.codec = Objects.requireNonNull(codec);
        this.handler = Objects.requireNonNull(handler);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public String id() { return "mqtt"; }
    @Override public Transport transport() { return Transport.MQTT; }
    @Override public boolean isRunning() { return running; }
    /** Approximate local observations; individual counters are not one atomic transaction. */
    public record RuntimeSnapshot(boolean running, String host, Integer port, int connections, int onlineDevices,
                                  int maxConnections, long acceptedMessages, long rejectedMessages,
                                  int activeWorkers, int workerThreads, int queuedWork, int maxQueuedWork) {}
    public RuntimeSnapshot runtimeSnapshot() {
        var pool = workers instanceof ThreadPoolExecutor executor ? executor : null;
        return new RuntimeSnapshot(running, options.host(), running ? actualPort() : null,
                connectionCount(), sessionCount(), options.maxConnections(), acceptedMessageCount(), rejectedMessageCount(),
                pool == null ? 0 : pool.getActiveCount(), options.workerThreads(),
                pool == null ? 0 : pool.getQueue().size(), options.maxQueuedWork());
    }
    public int connectionCount() { return connections.size(); }
    public int sessionCount() { return sessions.size(); }
    public long acceptedMessageCount() { return acceptedMessages.get(); }
    public long rejectedMessageCount() { return rejectedMessages.get(); }
    public int actualPort() { return running && server != null ? server.actualPort() : -1; }

    @Override
    public synchronized CompletionStage<Void> start() {
        if (running) return CompletableFuture.completedFuture(null);
        if (starting != null && !starting.isDone()) return starting;
        if (stopping != null && !stopping.isDone()) return CompletableFuture.failedFuture(new IllegalStateException("接入正在停止"));
        workers = new ThreadPoolExecutor(options.workerThreads(), options.workerThreads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(options.maxQueuedWork()), Thread.ofPlatform().daemon(true).name("mqtt-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        starting = new CompletableFuture<>();
        var result = starting;
        var candidate = MqttServer.create(vertx, new MqttServerOptions().setHost(options.host()).setPort(options.port())
                .setAutoClientId(false).setMaxClientIdLength(256).setMaxMessageSize(options.maxMessageBytes()));
        server = candidate;
        candidate.endpointHandler(this::onConnect).listen().onComplete(ar -> {
            synchronized (this) {
                if (ar.succeeded()) {
                    running = true;
                    LOG.log(System.Logger.Level.INFO, "MQTT gateway started host={0} port={1}", options.host(), Integer.toString(candidate.actualPort()));
                    if (ownership != null) ownershipTimer = vertx.setPeriodic(500, ignored -> maintainOwnership());
                    result.complete(null);
                } else {
                    candidate.close();
                    server = null;
                    workers.shutdown();
                    result.completeExceptionally(ar.cause());
                }
            }
        });
        return result;
    }

    @Override
    public synchronized CompletionStage<Void> stop() {
        if (starting != null && !starting.isDone()) {
            return starting.handle((ok, error) -> null).thenCompose(ignored -> stop());
        }
        if (stopping != null && !stopping.isDone()) return stopping;
        if (server == null) return CompletableFuture.completedFuture(null);
        running = false;
        LOG.log(System.Logger.Level.INFO, "MQTT gateway stopping sessions={0}", sessions.size());
        sessions.values().forEach(session -> session.closeReason = "SERVER_SHUTTING_DOWN");
        if (ownershipTimer != -1) { vertx.cancelTimer(ownershipTimer); ownershipTimer = -1; }
        stopping = new CompletableFuture<>();
        var result = stopping;
        var current = server;
        server = null;
        connections.forEach(VertxMqttGateway::closeEndpoint);
        current.close().onComplete(ar -> {
            List.copyOf(sessions.values()).forEach(session -> closeSession(session, new IllegalStateException("Gateway stopped")));
            workers.shutdown();
            sessions.clear();
            clientIds.clear();
            connections.clear();
            if (ar.succeeded()) result.complete(null); else result.completeExceptionally(ar.cause());
        });
        return result;
    }

    private void onConnect(MqttEndpoint endpoint) {
        var context = Vertx.currentContext();
        connections.add(endpoint);
        endpoint.closeHandler(ignored -> connections.remove(endpoint));
        if (!acceptsConnections() || connections.size() > options.maxConnections()) { refuse(endpoint, 0x89); return; }
        if (endpoint.protocolVersion() != 5) {
            LOG.log(System.Logger.Level.WARNING, "MQTT connection refused clientId={0} reason=UNSUPPORTED_PROTOCOL version={1}", logValue(endpoint.clientIdentifier()), endpoint.protocolVersion());
            endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION); return;
        }
        if (endpoint.clientIdentifier() == null || endpoint.clientIdentifier().isBlank()) { refuse(endpoint, 0x85); return; }
        if (endpoint.will() != null && endpoint.will().isWillFlag()) { refuse(endpoint, 0x83); return; }
        if (endpoint.connectProperties().getProperty(AUTHENTICATION_METHOD.value()) != null) { refuse(endpoint, 0x8C); return; }
        var auth = endpoint.auth();
        if (auth == null || auth.getUsername() == null || auth.getPassword() == null) { refuse(endpoint, 0x86); return; }
        var request = new MqttAuthenticationRequest(endpoint.clientIdentifier(), auth.getUsername(), auth.getPassword().getBytes(StandardCharsets.UTF_8));
        offload(() -> authenticator.authenticate(request).thenCompose(result -> {
            if (!(result instanceof AuthenticationResult.Accepted accepted)) {
                return CompletableFuture.<OwnedAccess>failedFuture(new SecurityException("设备认证拒绝"));
            }
            return resolveActive(accepted.device()).thenApply(access -> {
                long began = System.nanoTime();
                var lease = ownership == null ? null : ownership.acquire(access.device().key(), endpoint.clientIdentifier());
                return new OwnedAccess(access, lease, lease == null ? Long.MAX_VALUE : began + TimeUnit.MILLISECONDS.toNanos(lease.validForMillis()));
            });
        })).orTimeout(options.authenticationTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((access, error) -> {
                    request.close();
                    context.runOnContext(ignored -> {
                        if (!acceptsConnections() || !connections.contains(endpoint)) {
                            if (access != null) releaseOwnership(access.access().device().key(), access.lease());
                            closeEndpoint(endpoint); return;
                        }
                        if (error != null) {
                            LOG.log(System.Logger.Level.WARNING, "MQTT authentication failed clientId={0} errorType={1}",
                                    logValue(endpoint.clientIdentifier()), errorCause(error).getClass().getSimpleName());
                            refuse(endpoint, errorCause(error) instanceof SecurityException ? 0x87 : 0x88); return;
                        }
                        bind(endpoint, context, access);
                    });
                });
    }

    private synchronized void bind(MqttEndpoint endpoint, Context context, OwnedAccess owned) {
        var access = owned.access();
        var key = access.device().key();
        var collision = clientIds.get(endpoint.clientIdentifier());
        // 已被其他设备占用的ClientId不能劫持其连接，即使双方都通过认证。
        if (collision != null && !collision.key.equals(key)) { releaseOwnership(key, owned.lease()); refuse(endpoint, 0x85); return; }
        var session = new Session(endpoint, context, key,
                Math.min(options.maxInFlight(), intProperty(endpoint.connectProperties(), RECEIVE_MAXIMUM, 65535)),
                Integer.toUnsignedLong(intProperty(endpoint.connectProperties(), MAXIMUM_PACKET_SIZE, -1)));
        session.lease = owned.lease();
        session.ownershipDeadline = owned.deadline();
        session.nextRenewal = System.nanoTime() + (owned.lease() == null ? Long.MAX_VALUE / 4 : TimeUnit.MILLISECONDS.toNanos(owned.lease().validForMillis()) / 3);
        if (!leaseValid(session)) { releaseOwnership(key, session.lease); refuse(endpoint, 0x88); return; }
        var previous = sessions.put(key, session);
        clientIds.put(endpoint.clientIdentifier(), session);
        endpoint.closeHandler(ignored -> {
            connections.remove(endpoint);
            closeSession(session, new IllegalStateException("设备连接已关闭"));
        });
        endpoint.exceptionHandler(error -> { session.closeReason = "TRANSPORT_ERROR_" + error.getClass().getSimpleName(); closeSession(session, error); closeEndpoint(endpoint); });
        endpoint.disconnectMessageHandler(message -> { session.closeReason = "CLIENT_DISCONNECT"; /* Vert.x closes the endpoint after this callback. */ });
        endpoint.authenticationExchangeHandler(message -> disconnect(session, MqttDisconnectReasonCode.PROTOCOL_ERROR));
        endpoint.subscriptionAutoAck(false);
        endpoint.publishAutoAck(false);
        endpoint.subscribeHandler(message -> endpoint.subscribeAcknowledge(message.messageId(),
                Collections.nCopies(message.topicSubscriptions().size(), MqttSubAckReasonCode.NOT_AUTHORIZED), MqttProperties.NO_PROPERTIES));
        endpoint.unsubscribeHandler(message -> endpoint.unsubscribeAcknowledge(message.messageId(),
                Collections.nCopies(message.topics().size(), MqttUnsubAckReasonCode.NO_SUBSCRIPTION_EXISTED), MqttProperties.NO_PROPERTIES));
        endpoint.publishHandler(message -> onPublish(session, message));
        endpoint.publishAcknowledgeMessageHandler(message -> {
            var pending = session.outgoing.remove(message.messageId());
            if (pending != null) {
                vertx.cancelTimer(pending.timer);
                if (Byte.toUnsignedInt(message.code().value()) < 0x80) pending.result.complete(new DeliveryReceipt(pending.receipt.messageId(), pending.receipt.device(), clock.instant()));
                else pending.result.completeExceptionally(new IllegalStateException("设备拒绝传输，原因码=" + Byte.toUnsignedInt(message.code().value())));
            }
        });
        var properties = new MqttProperties();
        add(properties, MAXIMUM_QOS, 1);
        add(properties, RETAIN_AVAILABLE, 0);
        add(properties, RECEIVE_MAXIMUM, options.maxInFlight());
        add(properties, MAXIMUM_PACKET_SIZE, options.maxMessageBytes());
        add(properties, SESSION_EXPIRY_INTERVAL, 0);
        add(properties, TOPIC_ALIAS_MAXIMUM, 0);
        add(properties, WILDCARD_SUBSCRIPTION_AVAILABLE, 0);
        add(properties, SHARED_SUBSCRIPTION_AVAILABLE, 0);
        add(properties, SUBSCRIPTION_IDENTIFIER_AVAILABLE, 0);
        endpoint.accept(false, properties);
        LOG.log(System.Logger.Level.INFO, "MQTT connected connectionId={0} clientId={1} tenantId={2} deviceId={3} remote={4}",
                session.connectionId, logValue(endpoint.clientIdentifier()), logValue(key.tenantId().value()), logValue(key.deviceId().value()), logValue(endpoint.remoteAddress()));
        connectionChanged(key, true);
        if (previous != null) previous.context.runOnContext(ignored -> disconnect(previous, MqttDisconnectReasonCode.SESSION_TAKEN_OVER));
    }

    private void onPublish(Session session, MqttPublishMessage message) {
        if (!current(session)) { closeEndpoint(session.endpoint); return; }
        if (message.qosLevel().value() > 1) { reject(session, MqttDisconnectReasonCode.QOS_NOT_SUPPORTED); return; }
        if (message.isRetain()) { reject(session, MqttDisconnectReasonCode.RETAIN_NOT_SUPPORTED); return; }
        if (message.properties().getProperty(TOPIC_ALIAS.value()) != null) { reject(session, MqttDisconnectReasonCode.TOPIC_ALIAS_INVALID); return; }

        if (session.pendingIngress >= options.maxInFlight()) { reject(session, MqttDisconnectReasonCode.RECEIVE_MAXIMUM_EXCEEDED); return; }
        session.pendingIngress++;
        var receivedAt = clock.instant();
        var packet = new ProtocolPacket(new BinaryPayload(message.payload().getBytes()), Map.of("topic", message.topicName()));
        // 同一设备的消息顺序处理，工作队列及在途数量均有上限。
        session.ingress = session.ingress.handle((ok, error) -> null).thenCompose(ignored -> offload(() -> {
            if (!current(session)) return CompletableFuture.failedFuture(new IllegalStateException("会话已失效"));
            refreshOwnership(session);
            return resolveActive(session.key).thenCompose(access -> {
                var decodeContext = new DecodeContext(session.key, Transport.MQTT, access.device().modelVersion(), receivedAt);
                var decoded = List.copyOf(codec.decode(packet, decodeContext));
                if (decoded.size() > 64) throw new IllegalArgumentException("单个报文消息过多");
                CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
                for (var item : decoded) {
                    var enriched = decodeContext.bind(item);
                    if (enriched.body() instanceof DeviceMessageBody.PropertyReport report) access.model().validateReport(report.properties());
                    if (enriched.body() instanceof DeviceMessageBody.PropertyReadReply reply) access.model().validateReport(reply.properties());
                    if (enriched.body() instanceof DeviceMessageBody.PropertyWriteReply reply) access.model().validatePropertyWrite(reply.properties());
                    if (enriched.body() instanceof DeviceMessageBody.EventReport event) access.model().validateEvent(event.eventId(), event.fields());
                    chain = chain.thenComposeAsync(nothing -> current(session) ? handler.handle(enriched)
                            : CompletableFuture.failedFuture(new IllegalStateException("会话已失效")), workers);
                }
                return chain;
            });
        }).orTimeout(options.processingTimeout().toMillis(), TimeUnit.MILLISECONDS));
        session.ingress.whenComplete((ok, error) -> session.context.runOnContext(ignored -> {
            session.pendingIngress--;
            if (!current(session)) return;
            if (error != null) {
                var cause = errorCause(error);
                if (cause instanceof SecurityException) {
                    reject(session, MqttDisconnectReasonCode.NOT_AUTHORIZED);
                    return;
                }
                rejectedMessages.incrementAndGet();
                // 应用载荷或处理失败只拒绝当前消息，不终止有效的MQTT会话。
                if (message.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
                    var reason = cause instanceof IllegalArgumentException
                            ? MqttPubAckReasonCode.PAYLOAD_FORMAT_INVALID
                            : MqttPubAckReasonCode.IMPLEMENTATION_SPECIFIC_ERROR;
                    session.endpoint.publishAcknowledge(message.messageId(), reason, MqttProperties.NO_PROPERTIES);
                }
                return;
            }
            acceptedMessages.incrementAndGet();
            // 接收端口成功后才确认；这不自动等于数据已持久化。
            if (message.qosLevel() == MqttQoS.AT_LEAST_ONCE) session.endpoint.publishAcknowledge(message.messageId());
        }));
    }

    @Override
    public CompletionStage<DeliveryReceipt> send(DeviceDownlink command) {return sendToSession(command,null,0);}
    /** Router must supply the expected session token; a new connection must not inherit an old dispatch. */
    public CompletionStage<DeliveryReceipt> sendToSession(DeviceDownlink command,String expectedSession,long expectedEpoch) {
        Objects.requireNonNull(command, "command");
        if (!running || command.isExpiredAt(clock.instant())) return CompletableFuture.failedFuture(new IllegalStateException("接入未运行或命令已过期"));
        var session = sessions.get(command.device());
        if (session == null) return CompletableFuture.failedFuture(new IllegalStateException("目标设备不在线"));
        if(expectedSession!=null && (session.lease==null || !session.lease.sessionId().equals(expectedSession) || session.lease.epoch()!=expectedEpoch))
            return CompletableFuture.failedFuture(new IllegalStateException("Router session token is stale"));
        var result = new CompletableFuture<DeliveryReceipt>();
        offload(() -> {
            refreshOwnership(session);
            return resolveActive(command.device()).thenApply(access -> {
            if (!command.modelVersion().equals(access.device().modelVersion())) throw new IllegalArgumentException("命令模型版本不匹配");
            switch (command.body()) {
                case DownlinkBody.OperationInvoke invoke -> access.model().validateOperation(invoke.operationId(), invoke.inputs());
                case DownlinkBody.PropertyRead read -> access.model().validatePropertyRead(read.properties());
                case DownlinkBody.PropertyWrite write -> access.model().validatePropertyWrite(write.properties());
                default -> { }
            }
            return codec.encode(command, Transport.MQTT);
        }); }).orTimeout(options.processingTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((packet, error) -> session.context.runOnContext(ignored -> {
                    if (error != null) { result.completeExceptionally(errorCause(error)); return; }
                    publish(session, command, packet, result);
                }));
        return result;
    }

    private void publish(Session session, DeviceDownlink command, ProtocolPacket packet, CompletableFuture<DeliveryReceipt> result) {
        if (!current(session) || command.isExpiredAt(clock.instant())) { result.completeExceptionally(new IllegalStateException("会话失效或命令过期")); return; }
        if (packet == null || !validTopic(packet.attributes().get("topic"))) { result.completeExceptionally(new IllegalArgumentException("下行编码必须使用设备下行通道")); return; }
        // 预留协议字段开销，避免超过客户端声明的最大报文。
        if (packet.payload().size() + packet.attributes().get("topic").getBytes(StandardCharsets.UTF_8).length + 128L > Math.min(session.maxPacketBytes, options.maxMessageBytes())) {
            result.completeExceptionally(new IllegalArgumentException("下行报文超过限制")); return;
        }
        if (session.outgoing.size() >= session.receiveMaximum) { result.completeExceptionally(new RejectedExecutionException("设备下行在途窗口已满")); return; }
        int packetId = session.nextPacketId();
        var receipt = new DeliveryReceipt(command.messageId(), command.device(), clock.instant());
        long remainingMillis = Duration.between(clock.instant(), command.expiresAt()).toMillis();
        if (remainingMillis < 1) { result.completeExceptionally(new IllegalStateException("命令已过期")); return; }
        var properties = new MqttProperties();
        add(properties, PUBLICATION_EXPIRY_INTERVAL, (int) Math.min(0xFFFFFFFFL, (remainingMillis + 999) / 1000));
        var pending = new Pending(result, receipt);
        session.outgoing.put(packetId, pending);
        pending.timer = vertx.setTimer(Math.min(remainingMillis, options.acknowledgementTimeout().toMillis()), ignored -> {
            // 未确认的包标识不能直接复用；关闭连接后由命令域决定查询或重试。
            result.completeExceptionally(new TimeoutException("等待MQTT PUBACK超时，执行结果未知"));
            disconnect(session, MqttDisconnectReasonCode.IMPLEMENTATION_SPECIFIC_ERROR);
        });
        try {
            session.endpoint.publish(packet.attributes().get("topic"), Buffer.buffer(packet.payload().copyBytes()), MqttQoS.AT_LEAST_ONCE,
                    false, false, packetId, properties).onFailure(error -> {
                result.completeExceptionally(error);
                disconnect(session, MqttDisconnectReasonCode.IMPLEMENTATION_SPECIFIC_ERROR);
            });
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
            disconnect(session, MqttDisconnectReasonCode.IMPLEMENTATION_SPECIFIC_ERROR);
        }
    }

    /** 管理域撤销资格时可主动断开；注册域仍需在后续认证及操作中持续拒绝。 */
    public CompletionStage<Void> disconnectDevice(DeviceKey key) {
        var session = sessions.get(key);
        if (session == null) return CompletableFuture.completedFuture(null);
        var result = new CompletableFuture<Void>();
        session.context.runOnContext(ignored -> { disconnect(session, MqttDisconnectReasonCode.ADMINISTRATIVE_ACTION); result.complete(null); });
        return result;
    }

    private CompletionStage<DeviceAccessContext> resolveActive(DeviceKey key) {
        return resolver.resolve(key).thenApplyAsync(found -> {
            var access = found.orElseThrow(() -> new SecurityException("设备未注册"));
            if (!key.equals(access.device().key()) || access.device().lifecycle() != DeviceLifecycle.ACTIVE) throw new SecurityException("设备不允许接入");
            return access;
        }, workers);
    }

    private <T> CompletableFuture<T> offload(Supplier<CompletionStage<T>> task) {
        try {
            return CompletableFuture.supplyAsync(task, workers).thenCompose(CompletionStage::toCompletableFuture);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static boolean validTopic(String topic) {
        return topic != null && !topic.isBlank() && topic.indexOf('#') < 0 && topic.indexOf('+') < 0
                && topic.indexOf(0) < 0 && topic.getBytes(StandardCharsets.UTF_8).length <= 1024;
    }
    private boolean leaseValid(Session session) {
        return serviceGuard.getAsBoolean() && (session.lease == null || session.ownershipDeadline - System.nanoTime() > 0);
    }
    private boolean current(Session session) { return running && !session.closed && sessions.get(session.key) == session && leaseValid(session); }
    private record OwnedAccess(DeviceAccessContext access, SessionOwnership.Lease lease, long deadline) {}
    private void refreshOwnership(Session session) {
        if (session.lease == null) return;
        if (!current(session)) throw new SecurityException("Session ownership is no longer valid");
        long began = System.nanoTime();
        try {
            var renewed = ownership.renew(session.key, session.lease);
            session.ownershipDeadline = began + TimeUnit.MILLISECONDS.toNanos(renewed.validForMillis());
            session.nextRenewal = began + TimeUnit.MILLISECONDS.toNanos(renewed.validForMillis()) / 3;
            if (!current(session)) throw new SecurityException("Session changed while renewing ownership");
        } catch (RuntimeException error) {
            session.ownershipDeadline = 0;
            session.context.runOnContext(ignored -> disconnect(session, MqttDisconnectReasonCode.SESSION_TAKEN_OVER));
            throw error;
        }
    }
    private void maintainOwnership() {
        for (var session : sessions.values()) {
            if (!current(session)) { session.context.runOnContext(ignored -> disconnect(session, MqttDisconnectReasonCode.SESSION_TAKEN_OVER)); continue; }
            if (session.nextRenewal - System.nanoTime() > 0 || !session.renewing.compareAndSet(false, true)) continue;
            offload(() -> { refreshOwnership(session); return CompletableFuture.completedFuture(null); })
                    .whenComplete((ok, error) -> {
                        session.renewing.set(false);
                        if (error != null && !(errorCause(error) instanceof RejectedExecutionException)) session.context.runOnContext(ignored -> disconnect(session, MqttDisconnectReasonCode.IMPLEMENTATION_SPECIFIC_ERROR));
                    });
        }
    }
    private void releaseOwnership(DeviceKey key, SessionOwnership.Lease lease) {
        if (ownership == null || lease == null) return;
        offload(() -> { ownership.release(key, lease); return CompletableFuture.completedFuture(null); })
                .exceptionally(error -> { System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Session release deferred to lease expiry", error); return null; });
    }
    private void reject(Session session, MqttDisconnectReasonCode reason) { rejectedMessages.incrementAndGet(); disconnect(session, reason); }
    private void disconnect(Session session, MqttDisconnectReasonCode reason) {
        session.closeReason = reason.name();
        closeSession(session, new IllegalStateException("连接结束：" + reason));
        try { if (session.endpoint.isConnected()) session.endpoint.disconnect(reason, MqttProperties.NO_PROPERTIES); }
        finally { connections.remove(session.endpoint); closeEndpoint(session.endpoint); }
    }
    private synchronized void closeSession(Session session, Throwable reason) {
        if (session.closed) return;
        session.closed = true;
        LOG.log(System.Logger.Level.INFO, "MQTT disconnected connectionId={0} clientId={1} tenantId={2} deviceId={3} reason={4} durationMs={5}",
                session.connectionId, logValue(session.endpoint.clientIdentifier()), logValue(session.key.tenantId().value()),
                logValue(session.key.deviceId().value()), session.closeReason, Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - session.connectedAt)));
        // 比较后删除，防止旧连接的关闭回调移除新会话。
        if (sessions.remove(session.key, session)) connectionChanged(session.key, false);
        clientIds.remove(session.endpoint.clientIdentifier(), session);
        session.outgoing.values().forEach(pending -> { vertx.cancelTimer(pending.timer); pending.result.completeExceptionally(reason); });
        session.outgoing.clear();
        releaseOwnership(session.key, session.lease);
    }
    private static void closeEndpoint(MqttEndpoint endpoint) {
        // Vert.x的disconnect已标记端点关闭，重复清理不应破坏网关停止流程。
        try { endpoint.close(); } catch (IllegalArgumentException alreadyClosed) { }
    }
    private static void refuse(MqttEndpoint endpoint, int reason) {
        var code = MqttConnectReturnCode.valueOf((byte) reason);
        LOG.log(System.Logger.Level.WARNING, "MQTT connection refused clientId={0} remote={1} reason={2}",
                logValue(endpoint.clientIdentifier()), logValue(endpoint.remoteAddress()), code.name());
        endpoint.reject(code);
    }
    /** Client-controlled identifiers must stay bounded and cannot inject additional log lines. */
    private static String logValue(Object value) {
        if (value == null) return "-";
        var text = value.toString();
        return text.substring(0, Math.min(text.length(), 256)).replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", "_");
    }
    private static void add(MqttProperties properties, MqttProperties.MqttPropertyType type, int value) { properties.add(new MqttProperties.IntegerProperty(type.value(), value)); }
    private static int intProperty(MqttProperties properties, MqttProperties.MqttPropertyType type, int fallback) {
        var property = properties.getProperty(type.value());
        return property instanceof MqttProperties.IntegerProperty number ? number.value() : fallback;
    }
    private static Throwable errorCause(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static final class Session {
        final String connectionId = UUID.randomUUID().toString();
        final long connectedAt = System.nanoTime();
        String closeReason = "CONNECTION_CLOSED";
        final MqttEndpoint endpoint;
        final Context context;
        final DeviceKey key;
        final int receiveMaximum;
        final long maxPacketBytes;
        final Map<Integer, Pending> outgoing = new HashMap<>();
        volatile boolean closed;
        SessionOwnership.Lease lease;
        volatile long ownershipDeadline = Long.MAX_VALUE;
        volatile long nextRenewal;
        final java.util.concurrent.atomic.AtomicBoolean renewing = new java.util.concurrent.atomic.AtomicBoolean();
        int cursor;
        int pendingIngress;
        CompletionStage<Void> ingress = CompletableFuture.completedFuture(null);
        Session(MqttEndpoint endpoint, Context context, DeviceKey key, int receiveMaximum, long maxPacketBytes) {
            this.endpoint = endpoint; this.context = context; this.key = key;
            this.receiveMaximum = receiveMaximum; this.maxPacketBytes = maxPacketBytes;
        }
        int nextPacketId() {
            do { cursor = cursor % 65535 + 1; } while (outgoing.containsKey(cursor));
            return cursor;
        }
    }
    private static final class Pending {
        final CompletableFuture<DeliveryReceipt> result;
        final DeliveryReceipt receipt;
        long timer;
        Pending(CompletableFuture<DeliveryReceipt> result, DeliveryReceipt receipt) { this.result = result; this.receipt = receipt; }
    }
}

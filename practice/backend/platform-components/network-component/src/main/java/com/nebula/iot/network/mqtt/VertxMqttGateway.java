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
        stopping = new CompletableFuture<>();
        var result = stopping;
        var current = server;
        server = null;
        connections.forEach(VertxMqttGateway::closeEndpoint);
        current.close().onComplete(ar -> {
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
        if (!running || connections.size() > options.maxConnections()) { refuse(endpoint, 0x89); return; }
        if (endpoint.protocolVersion() != 5) {
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
                return CompletableFuture.<DeviceAccessContext>failedFuture(new SecurityException("设备认证拒绝"));
            }
            return resolveActive(accepted.device());
        })).orTimeout(options.authenticationTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((access, error) -> {
                    request.close();
                    context.runOnContext(ignored -> {
                        if (!running || !connections.contains(endpoint)) { closeEndpoint(endpoint); return; }
                        if (error != null) { refuse(endpoint, errorCause(error) instanceof SecurityException ? 0x87 : 0x88); return; }
                        bind(endpoint, context, access);
                    });
                });
    }

    private synchronized void bind(MqttEndpoint endpoint, Context context, DeviceAccessContext access) {
        var key = access.device().key();
        var collision = clientIds.get(endpoint.clientIdentifier());
        // 已被其他设备占用的ClientId不能劫持其连接，即使双方都通过认证。
        if (collision != null && !collision.key.equals(key)) { refuse(endpoint, 0x85); return; }
        var session = new Session(endpoint, context, key,
                Math.min(options.maxInFlight(), intProperty(endpoint.connectProperties(), RECEIVE_MAXIMUM, 65535)),
                Integer.toUnsignedLong(intProperty(endpoint.connectProperties(), MAXIMUM_PACKET_SIZE, -1)));
        var previous = sessions.put(key, session);
        clientIds.put(endpoint.clientIdentifier(), session);
        endpoint.closeHandler(ignored -> {
            connections.remove(endpoint);
            closeSession(session, new IllegalStateException("设备连接已关闭"));
        });
        endpoint.exceptionHandler(error -> { closeSession(session, error); closeEndpoint(endpoint); });
        endpoint.disconnectMessageHandler(message -> { /* Vert.x closes the endpoint after this callback. */ });
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
    public CompletionStage<DeliveryReceipt> send(DeviceDownlink command) {
        Objects.requireNonNull(command, "command");
        if (!running || command.isExpiredAt(clock.instant())) return CompletableFuture.failedFuture(new IllegalStateException("接入未运行或命令已过期"));
        var session = sessions.get(command.device());
        if (session == null) return CompletableFuture.failedFuture(new IllegalStateException("目标设备不在线"));
        var result = new CompletableFuture<DeliveryReceipt>();
        offload(() -> resolveActive(command.device()).thenApply(access -> {
            if (!command.modelVersion().equals(access.device().modelVersion())) throw new IllegalArgumentException("命令模型版本不匹配");
            switch (command.body()) {
                case DownlinkBody.OperationInvoke invoke -> access.model().validateOperation(invoke.operationId(), invoke.inputs());
                case DownlinkBody.PropertyRead read -> access.model().validatePropertyRead(read.properties());
                case DownlinkBody.PropertyWrite write -> access.model().validatePropertyWrite(write.properties());
                default -> { }
            }
            return codec.encode(command, Transport.MQTT);
        })).orTimeout(options.processingTimeout().toMillis(), TimeUnit.MILLISECONDS)
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
    private boolean current(Session session) { return running && !session.closed && sessions.get(session.key) == session; }
    private void reject(Session session, MqttDisconnectReasonCode reason) { rejectedMessages.incrementAndGet(); disconnect(session, reason); }
    private void disconnect(Session session, MqttDisconnectReasonCode reason) {
        closeSession(session, new IllegalStateException("连接结束：" + reason));
        try { if (session.endpoint.isConnected()) session.endpoint.disconnect(reason, MqttProperties.NO_PROPERTIES); }
        finally { connections.remove(session.endpoint); closeEndpoint(session.endpoint); }
    }
    private void closeSession(Session session, Throwable reason) {
        if (session.closed) return;
        session.closed = true;
        // 比较后删除，防止旧连接的关闭回调移除新会话。
        sessions.remove(session.key, session);
        clientIds.remove(session.endpoint.clientIdentifier(), session);
        session.outgoing.values().forEach(pending -> { vertx.cancelTimer(pending.timer); pending.result.completeExceptionally(reason); });
        session.outgoing.clear();
    }
    private static void closeEndpoint(MqttEndpoint endpoint) {
        // Vert.x的disconnect已标记端点关闭，重复清理不应破坏网关停止流程。
        try { endpoint.close(); } catch (IllegalArgumentException alreadyClosed) { }
    }
    private static void refuse(MqttEndpoint endpoint, int reason) { endpoint.reject(MqttConnectReturnCode.valueOf((byte) reason)); }
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
        final MqttEndpoint endpoint;
        final Context context;
        final DeviceKey key;
        final int receiveMaximum;
        final long maxPacketBytes;
        final Map<Integer, Pending> outgoing = new HashMap<>();
        volatile boolean closed;
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

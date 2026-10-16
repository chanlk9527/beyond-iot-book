package com.nebula.iot.network.mqtt;

import com.nebula.iot.core.command.*;
import com.nebula.iot.protocol.official.*;
import com.nebula.iot.core.device.*;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.*;
import com.nebula.iot.gateway.*;
import io.vertx.core.Vertx;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实MQTT 5网络客户端验证，正常用例不发送任何SUBSCRIBE。 */
class VertxMqttGatewayTest {
    private final List<Client> clients = new ArrayList<>();
    private final BlockingQueue<DeviceMessage> inbound = new LinkedBlockingQueue<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private Vertx vertx;
    private VertxMqttGateway gateway;
    private DevelopmentDeviceDirectory directory;
    private static final byte[] PASSWORD = "test-password".getBytes(StandardCharsets.UTF_8);
    private static final DeviceKey A = new DeviceKey(new TenantId("tenant-a"), new DeviceId("warmer-1"));
    private static final DeviceKey B = new DeviceKey(new TenantId("tenant-b"), new DeviceId("warmer-1"));

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        directory = new DevelopmentDeviceDirectory(List.of(
                new DevelopmentDeviceDirectory.Registration("client-a", "a", PASSWORD, access(A)),
                new DevelopmentDeviceDirectory.Registration("client-b", "b", PASSWORD, access(B))));
        gateway = create(0, message -> { inbound.add(message); return CompletableFuture.completedFuture(null); });
        gateway.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private VertxMqttGateway create(int port, DeviceMessageHandler handler) {
        var options = new MqttGatewayOptions("127.0.0.1", port, 20, 65536, 8, 2, 32,
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2));
        DeviceAccessResolver resolver = key -> directory.resolve(key).thenApply(found -> enabled.get() ? found : Optional.empty());
        return new VertxMqttGateway(vertx, options, directory, resolver, new OfficialMqttCodec(), new OfficialSystemMessageHandler(message -> gateway.send(message), handler, Clock.systemUTC()));
    }

    @AfterEach
    void stop() throws Exception {
        for (var client : clients) client.close();
        gateway.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void sendsToOnlyTheSpecifiedTenantWithoutClientSubscriptions() throws Exception {
        var a = connect("client-a", "a", PASSWORD);
        var b = connect("client-b", "b", PASSWORD);
        var command = command(A, "c1", 70);
        var receipt = gateway.send(command).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var received = a.messages.poll(2, TimeUnit.SECONDS);
        assertNotNull(received);
        assertEquals("nebula/v1/down", received.topic);
        assertTrue(received.body.contains("\"commandId\":\"c1\""));
        assertEquals(command.commandId(), receipt.commandId());
        assertEquals(A, receipt.device());
        assertNull(b.messages.poll(200, TimeUnit.MILLISECONDS));
        assertEquals(0, gateway.acceptedMessageCount());
    }

    @Test
    void bindsUplinkIdentityAndAcknowledgesOnlyAfterAcceptance() throws Exception {
        var a = connect("client-a", "a", PASSWORD);
        a.client.publish("nebula/v1/up", report(70), 1, false);
        var message = inbound.poll(2, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(A, message.device());
        assertEquals("v1", message.modelVersion());
        assertInstanceOf(DeviceMessageBody.PropertyReport.class, message.body());
        assertEquals(1, gateway.acceptedMessageCount());
    }

    @Test
    void withholdsPubackUntilAsynchronousHandlerCompletes() throws Exception {
        gateway.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var gate = new CompletableFuture<Void>();
        gateway = create(0, message -> { inbound.add(message); return gate; });
        gateway.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var a = connect("client-a", "a", PASSWORD);
        var publishing = CompletableFuture.runAsync(() -> {
            try { a.client.publish("nebula/v1/up", report(70), 1, false); }
            catch (MqttException error) { throw new CompletionException(error); }
        });
        assertNotNull(inbound.poll(2, TimeUnit.SECONDS));
        assertFalse(publishing.isDone());
        gate.complete(null);
        publishing.get(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectsBadCredentialsAndClientIdImpersonation() throws Exception {
        assertThrows(MqttException.class, () -> connect("client-a", "a", "wrong".getBytes(StandardCharsets.UTF_8)));
        assertThrows(MqttException.class, () -> connect("client-b", "a", PASSWORD));
        await(() -> gateway.sessionCount() == 0);
    }

    @Test
    void rejectsSubscriptionRequestsWithoutPreventingDirectDelivery() throws Exception {
        var a = connect("client-a", "a", PASSWORD);
        try {
            var token = a.client.subscribe("nebula/v1/down", 1);
            assertTrue(Arrays.stream(token.getReasonCodes()).allMatch(code -> code >= 128));
        } catch (MqttException expected) {
            assertTrue(a.client.isConnected());
        }
        gateway.send(command(A, "c-sub", 60)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(a.messages.poll(2, TimeUnit.SECONDS));
    }

    @Test
    void rejectsWrongTopicAndInvalidModelWithoutDisconnecting() throws Exception {
        var a = connect("client-a", "a", PASSWORD);
        for (int qos : List.of(0, 1)) {
            var payloads = List.of(report(70), report(101), "not-json".getBytes(StandardCharsets.UTF_8),
                    "{}".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < payloads.size(); i++) {
                long rejected = gateway.rejectedMessageCount();
                try { a.client.publish(i == 0 ? "nebula/v1/down" : "nebula/v1/up", payloads.get(i), qos, false); }
                catch (MqttException expected) { assertEquals(1, qos); }
                await(() -> gateway.rejectedMessageCount() == rejected + 1);
                assertTrue(a.client.isConnected());
                assertEquals(1, gateway.sessionCount());
                assertTrue(inbound.isEmpty());
                a.client.publish("nebula/v1/up", report(70), 1, false);
                assertNotNull(inbound.poll(2, TimeUnit.SECONDS));
            }
        }
        await(() -> gateway.acceptedMessageCount() == 8);
    }

    @Test
    void handlerFailureDoesNotDisconnectOrPoisonFollowingMessages() throws Exception {
        gateway.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var fail = new AtomicBoolean(true);
        gateway = create(0, message -> {
            if (fail.getAndSet(false)) return CompletableFuture.failedFuture(new IllegalStateException("handler failed"));
            inbound.add(message);
            return CompletableFuture.completedFuture(null);
        });
        gateway.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var a = connect("client-a", "a", PASSWORD);
        try { a.client.publish("nebula/v1/up", report(70), 1, false); }
        catch (MqttException expected) { assertTrue(a.client.isConnected()); }
        await(() -> gateway.rejectedMessageCount() == 1);
        assertTrue(a.client.isConnected());
        a.client.publish("nebula/v1/up", report(70), 1, false);
        assertNotNull(inbound.poll(2, TimeUnit.SECONDS));
        await(() -> gateway.acceptedMessageCount() == 1);
    }

    @Test
    void newConnectionReplacesOldSessionWithoutBeingRemovedByItsCloseCallback() throws Exception {
        var previous = connect("client-a", "a", PASSWORD);
        var replacement = connect("client-a", "a", PASSWORD);
        await(() -> !previous.client.isConnected());
        gateway.send(command(A, "c-reconnect", 30)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(replacement.messages.poll(2, TimeUnit.SECONDS));
        assertTrue(replacement.client.isConnected());
        assertEquals(1, gateway.sessionCount());
    }

    @Test
    void honorsReceiveMaximumAndTracksPubackWithoutCallingItExecutionSuccess() throws Exception {
        var a = new Client("client-a");
        clients.add(a);
        a.client.setManualAcks(true);
        var options = connectionOptions("a", PASSWORD);
        options.setReceiveMaximum(1);
        a.client.connect(options);
        var first = gateway.send(command(A, "c-window1", 70)).toCompletableFuture();
        var received = a.messages.poll(2, TimeUnit.SECONDS);
        assertNotNull(received);
        assertFalse(first.isDone());
        assertThrows(ExecutionException.class, () -> gateway.send(command(A, "c-window2", 30)).toCompletableFuture().get(2, TimeUnit.SECONDS));
        a.client.messageArrivedComplete(received.packetId, 1);
        assertEquals(new CommandId("c-window1"), first.get(3, TimeUnit.SECONDS).commandId());
        assertTrue(inbound.isEmpty());
    }

    @Test
    void timesOutUnacknowledgedDownlinkAndReleasesSession() throws Exception {
        var a = new Client("client-a");
        clients.add(a);
        a.client.setManualAcks(true);
        a.client.connect(connectionOptions("a", PASSWORD));
        var sent = gateway.send(command(A, "c-timeout", 70)).toCompletableFuture();
        assertNotNull(a.messages.poll(2, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> sent.get(5, TimeUnit.SECONDS));
        await(() -> gateway.sessionCount() == 0);
    }

    @Test
    void rechecksDeviceEligibilityBeforeSending() throws Exception {
        var a = connect("client-a", "a", PASSWORD);
        enabled.set(false);
        assertThrows(ExecutionException.class, () -> gateway.send(command(A, "c-disabled", 70)).toCompletableFuture().get(3, TimeUnit.SECONDS));
        assertTrue(a.messages.isEmpty());
    }

    @Test
    void rejectsExpiredUnknownAndInvalidCommands() throws Exception {
        connect("client-a", "a", PASSWORD);
        assertThrows(ExecutionException.class, () -> gateway.send(command(B, "c-offline", 70)).toCompletableFuture().get(3, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> gateway.send(command(A, "c-invalid", 101)).toCompletableFuture().get(3, TimeUnit.SECONDS));
        var expired = new DeviceCommand(new CommandId("c-expired"), A, "v1", "setHeatingLevel", Map.of("heatingLevel", new Value.IntegerValue(70)),
                Instant.now().minusSeconds(10), Instant.now().minusSeconds(5));
        assertThrows(ExecutionException.class, () -> gateway.send(expired).toCompletableFuture().get(3, TimeUnit.SECONDS));
    }

    @Test
    void failedBindDoesNotClaimReadinessAndPortCanBeReusedAfterStop() throws Exception {
        var second = create(gateway.actualPort(), message -> CompletableFuture.completedFuture(null));
        assertThrows(ExecutionException.class, () -> second.start().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertFalse(second.isRunning());
        gateway.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        second.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(second.isRunning());
        second.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectsMqttThreeInsteadOfSilentlyDowngrading() throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", gateway.actualPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(new byte[] {0x10, 15, 0, 4, 'M', 'Q', 'T', 'T', 4, 2, 0, 30, 0, 3, 'o', 'l', 'd'});
            var response = socket.getInputStream().readNBytes(4);
            assertArrayEquals(new byte[] {0x20, 2, 0, 1}, response);
        }
    }

    @Test
    void authenticationTimeoutDoesNotLeaveALateSession() throws Exception {
        gateway.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var authentication = new CompletableFuture<com.nebula.iot.core.auth.AuthenticationResult>();
        gateway = new VertxMqttGateway(vertx, new MqttGatewayOptions("127.0.0.1", 0, 20, 65536, 8, 2, 32,
                Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofSeconds(2)),
                request -> authentication, directory, new OfficialMqttCodec(), message -> CompletableFuture.completedFuture(null));
        gateway.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThrows(MqttException.class, () -> connect("client-a", "a", PASSWORD));
        authentication.complete(new com.nebula.iot.core.auth.AuthenticationResult.Accepted(A));
        await(() -> gateway.connectionCount() == 0);
        assertEquals(0, gateway.sessionCount());
    }
    @Test
    void routesPropertyConfigurationAndOtaRequestsAndCorrelatesRepliesWithoutSubscriptions() throws Exception {
        var client = connect("client-a", "a", PASSWORD);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var requests = List.of(
                new DownlinkBody.PropertyRead(List.of("heatingLevel")),
                new DownlinkBody.PropertyWrite(Map.of("heatingLevel", new Value.IntegerValue(50))),
                new DownlinkBody.ConfigurationRead("sampling"),
                new DownlinkBody.ConfigurationSet("sampling", "r2", Optional.of("r1"), Map.of("interval", new Value.IntegerValue(60))),
                new DownlinkBody.OtaUpgrade("t1", "a1", "main", "1.0", "2.0", "A",
                        new DownlinkBody.FirmwarePackage(java.net.URI.create("https://firmware.example.com/main.bin"), 1024,
                                "a".repeat(64), "k1", Base64.getEncoder().encodeToString(new byte[64]))),
                new DownlinkBody.OtaCancel("t1", "a1"));
        var methods = List.of("thing.property.read", "thing.property.write", "thing.config.get", "thing.config.set", "ota.upgrade", "ota.cancel");
        var replies = List.of("{\"properties\":{\"heatingLevel\":70}}", "{\"properties\":{\"heatingLevel\":50}}",
                "{\"scope\":\"sampling\",\"version\":\"r1\",\"values\":{\"interval\":30}}",
                "{\"scope\":\"sampling\",\"version\":\"r2\"}", "{\"taskId\":\"t1\",\"attemptId\":\"a1\"}", "{\"taskId\":\"t1\",\"attemptId\":\"a1\"}");
        for (int i = 0; i < requests.size(); i++) {
            var id = new MessageId("down-" + i);
            var down = new DeviceDownlink(id, A, "v1", Instant.now(), Instant.now().plusSeconds(30), Optional.empty(), Optional.empty(), requests.get(i));
            assertEquals(id, gateway.send(down).toCompletableFuture().get(5, TimeUnit.SECONDS).messageId());
            var received = client.messages.poll(2, TimeUnit.SECONDS); assertNotNull(received);
            assertEquals("nebula/v1/down", received.topic); assertEquals(methods.get(i), json.readTree(received.body).path("method").asText());
            var reply = "{\"version\":\"1.0\",\"messageId\":\"reply-" + i + "\",\"timestamp\":0,\"method\":\"" + methods.get(i)
                    + ".reply\",\"replyTo\":\"" + id.value() + "\",\"result\":{\"code\":\"SUCCESS\"},\"data\":" + replies.get(i) + "}";
            client.client.publish("nebula/v1/up", reply.getBytes(StandardCharsets.UTF_8), 1, false);
            var enriched = inbound.poll(2, TimeUnit.SECONDS); assertNotNull(enriched);
            assertEquals(A, enriched.device()); assertEquals(Optional.of(id), enriched.replyTo()); assertTrue(enriched.result().orElseThrow().isSuccess());
        }
    }

    @Test
    void repliesToTimeRequestOnSameConnectionWithoutSubscriptions() throws Exception {
        var client = connect("client-a", "a", PASSWORD);
        var request = "{\"version\":\"1.0\",\"messageId\":\"time-1\",\"timestamp\":0,\"method\":\"system.time.request\",\"data\":{\"clientSendTime\":1234}}";
        client.client.publish("nebula/v1/up", request.getBytes(StandardCharsets.UTF_8), 1, false);
        var received = client.messages.poll(2, TimeUnit.SECONDS); assertNotNull(received);
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.body);
        assertEquals("system.time.reply", root.path("method").asText()); assertEquals("time-1", root.path("replyTo").asText());
        assertEquals(1234, root.path("data").path("clientSendTime").longValue());
        assertTrue(root.path("data").path("serverSendTime").longValue() >= root.path("data").path("serverReceiveTime").longValue());
        assertTrue(inbound.isEmpty());
    }

    @Test
    void validatesPropertyReadAndWriteAgainstModelBeforeSending() throws Exception {
        var client = connect("client-a", "a", PASSWORD);
        for (var body : List.of(new DownlinkBody.PropertyRead(List.of("unknown")), new DownlinkBody.PropertyWrite(Map.of("heatingLevel", new Value.IntegerValue(101))))) {
            var down = new DeviceDownlink(new MessageId("invalid"), A, "v1", Instant.now(), Instant.now().plusSeconds(30), Optional.empty(), Optional.empty(), body);
            assertThrows(ExecutionException.class, () -> gateway.send(down).toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
        assertTrue(client.messages.isEmpty());
    }

    private Client connect(String id, String username, byte[] password) throws Exception {
        var client = new Client(id);
        clients.add(client);
        client.client.connect(connectionOptions(username, password));
        return client;
    }
    private MqttConnectionOptions connectionOptions(String username, byte[] password) {
        var options = new MqttConnectionOptions();
        options.setUserName(username); options.setPassword(password); options.setCleanStart(true);
        options.setSessionExpiryInterval(0L); options.setConnectionTimeout(3);
        return options;
    }
    private static DeviceAccessContext access(DeviceKey key) {
        var product = new ProductKey(key.tenantId(), new ProductId("warmer"));
        var heatingLevel = new ValueSchema(ValueType.INTEGER, BigDecimal.ONE, BigDecimal.valueOf(100), null);
        var model = new DeviceModel(product, "v1", Map.of("heatingLevel", new PropertyDefinition("heatingLevel", heatingLevel, PropertyAccess.READ_WRITE)), Map.of(),
                Map.of("setHeatingLevel", new OperationDefinition("setHeatingLevel", Map.of("heatingLevel", new FieldDefinition("heatingLevel", heatingLevel, true)))));
        return new DeviceAccessContext(new DeviceDescriptor(key, product, "v1", DeviceLifecycle.ACTIVE), model);
    }
    private static DeviceCommand command(DeviceKey key, String id, long value) {
        return new DeviceCommand(new CommandId(id), key, "v1", "setHeatingLevel", Map.of("heatingLevel", new Value.IntegerValue(value)), Instant.now(), Instant.now().plusSeconds(30));
    }
    private static byte[] report(int value) {
        return ("{\"version\":\"1.0\",\"method\":\"thing.property.report\",\"messageId\":\"m-1\",\"timestamp\":1792022400000,\"data\":{\"properties\":{\"heatingLevel\":" + value + "}}}").getBytes(StandardCharsets.UTF_8);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    private record Received(int packetId, String topic, String body) { }
    private final class Client implements AutoCloseable {
        final MqttClient client;
        final BlockingQueue<Received> messages = new LinkedBlockingQueue<>();
        Client(String id) throws MqttException {
            client = new MqttClient("tcp://127.0.0.1:" + gateway.actualPort(), id, new MemoryPersistence());
            client.setTimeToWait(5000);
            client.setCallback(new MqttCallback() {
                @Override public void messageArrived(String topic, MqttMessage message) {
                    messages.add(new Received(message.getId(), topic, new String(message.getPayload(), StandardCharsets.UTF_8)));
                }
                @Override public void disconnected(MqttDisconnectResponse response) { }
                @Override public void mqttErrorOccurred(MqttException error) { }
                @Override public void deliveryComplete(IMqttToken token) { }
                @Override public void connectComplete(boolean reconnect, String serverURI) { }
                @Override public void authPacketArrived(int reasonCode, MqttProperties properties) { }
            });
        }
        @Override public void close() throws MqttException {
            if (client.isConnected()) client.disconnectForcibly(0, 0, false);
            client.close(true);
        }
    }
}

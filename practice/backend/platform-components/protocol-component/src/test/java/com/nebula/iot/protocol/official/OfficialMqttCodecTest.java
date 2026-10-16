package com.nebula.iot.protocol.official;

import com.fasterxml.jackson.databind.*;
import com.nebula.iot.core.auth.*;
import com.nebula.iot.core.command.*;
import com.nebula.iot.core.device.*;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.*;
import com.nebula.iot.core.protocol.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** 协议语义、严格解析及系统响应验证；实际MQTT传输由网络组件集成测试覆盖。 */
class OfficialMqttCodecTest {
    private static final Instant NOW = Instant.ofEpochMilli(1792022400000L);
    private static final DeviceKey KEY = new DeviceKey(new TenantId("tenant-a"), new DeviceId("warmer-1"));
    private static final DecodeContext CONTEXT = new DecodeContext(KEY, Transport.MQTT, "v1", NOW);
    private static final OfficialMqttCodec CODEC = new OfficialMqttCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    private static final ObjectMapper JSON = new ObjectMapper();

    static Stream<Arguments> uplinks() {
        return Stream.of(
            Arguments.of("thing.property.report", "{\"properties\":{\"power\":true,\"heatingLevel\":70,\"temperature\":25.5,\"name\":\"灯\"}}", DeviceMessageBody.PropertyReport.class),
            Arguments.of("thing.property.read.reply", "{\"properties\":{\"heatingLevel\":70}}", DeviceMessageBody.PropertyReadReply.class),
            Arguments.of("thing.property.write.reply", "{\"properties\":{\"heatingLevel\":70}}", DeviceMessageBody.PropertyWriteReply.class),
            Arguments.of("thing.command.reply", "{\"commandId\":\"c1\",\"status\":\"SUCCEEDED\"}", DeviceMessageBody.CommandReply.class),
            Arguments.of("thing.event.report", "{\"eventId\":\"fault\",\"fields\":{\"reason\":\"overheat\"}}", DeviceMessageBody.EventReport.class),
            Arguments.of("thing.info.report", "{\"firmwareVersion\":\"1.0\",\"hardwareVersion\":\"A\",\"bootId\":\"b1\",\"sdkVersion\":\"1.0\",\"clockSynced\":true}", DeviceMessageBody.DeviceInfoReport.class),
            Arguments.of("thing.config.report", "{\"scope\":\"sampling\",\"version\":\"r1\",\"values\":{\"interval\":60}}", DeviceMessageBody.ConfigurationReport.class),
            Arguments.of("thing.config.get.reply", "{\"scope\":\"sampling\",\"version\":\"r1\",\"values\":{}}", DeviceMessageBody.ConfigurationReadReply.class),
            Arguments.of("thing.config.set.reply", "{\"scope\":\"sampling\",\"version\":\"r2\"}", DeviceMessageBody.ConfigurationSetReply.class),
            Arguments.of("system.time.request", "{\"clientSendTime\":0}", DeviceMessageBody.TimeSyncRequest.class),
            Arguments.of("ota.version.report", "{\"versions\":{\"main\":\"1.0\",\"radio\":\"2.0\"}}", DeviceMessageBody.OtaVersionReport.class),
            Arguments.of("ota.upgrade.reply", "{\"taskId\":\"t1\",\"attemptId\":\"a1\"}", DeviceMessageBody.OtaUpgradeReply.class),
            Arguments.of("ota.cancel.reply", "{\"taskId\":\"t1\",\"attemptId\":\"a1\"}", DeviceMessageBody.OtaCancelReply.class),
            Arguments.of("ota.progress.report", "{\"taskId\":\"t1\",\"attemptId\":\"a1\",\"upgradeMessageId\":\"upgrade-1\",\"component\":\"main\",\"targetVersion\":\"2.0\",\"stage\":\"SUCCEEDED\",\"progress\":100,\"installedVersion\":\"2.0\"}", DeviceMessageBody.OtaProgressReport.class));
    }
    @ParameterizedTest @MethodSource("uplinks")
    void decodesAllUplinkSemantics(String method, String data, Class<?> expected) {
        var decoded = decode(envelope(method, data, "SUCCESS"));
        assertInstanceOf(expected, decoded.body());
        assertEquals(method.endsWith(".reply"), decoded.replyTo().isPresent());
        var bound = CONTEXT.bind(decoded);
        assertEquals(KEY, bound.device()); assertEquals("v1", bound.modelVersion()); assertEquals(NOW, bound.receivedAt());
        assertEquals(decoded.replyTo(), bound.replyTo()); assertEquals(decoded.result(), bound.result());
    }
    @ParameterizedTest @ValueSource(strings = {"thing.property.read.reply", "thing.property.write.reply", "thing.config.get.reply", "thing.config.set.reply", "ota.upgrade.reply", "ota.cancel.reply"})
    void decodesFailedRequestWithoutInventingSuccessData(String method) {
        var decoded = decode(envelope(method, "{}", "BUSY"));
        assertInstanceOf(DeviceMessageBody.RequestFailure.class, decoded.body());
        assertEquals("BUSY", decoded.result().orElseThrow().code());
        assertThrows(IllegalArgumentException.class, () -> decode(envelope(method, "{\"unexpected\":true}", "BUSY")));
    }
    @Test void validatesCommandStatusAgainstResult() {
        assertInstanceOf(DeviceMessageBody.CommandReply.class, decode(envelope("thing.command.reply", "{\"commandId\":\"c1\",\"status\":\"ACCEPTED\"}", "SUCCESS")).body());
        var failed = (DeviceMessageBody.CommandReply) decode(envelope("thing.command.reply", "{\"commandId\":\"c1\",\"status\":\"FAILED\"}", "BUSY")).body();
        assertEquals(Optional.of("BUSY"), failed.errorCode());
        assertThrows(IllegalArgumentException.class, () -> decode(envelope("thing.command.reply", "{\"commandId\":\"c1\",\"status\":\"FAILED\"}", "SUCCESS")));
    }
    static Stream<Arguments> downlinks() {
        return Stream.of(
            Arguments.of(new DownlinkBody.PropertyRead(List.of("heatingLevel")), "thing.property.read"),
            Arguments.of(new DownlinkBody.PropertyWrite(Map.of("heatingLevel", new Value.IntegerValue(70))), "thing.property.write"),
            Arguments.of(new DownlinkBody.OperationInvoke(new CommandId("c1"), "setHeatingLevel", Map.of()), "thing.command.invoke"),
            Arguments.of(new DownlinkBody.ConfigurationRead("sampling"), "thing.config.get"),
            Arguments.of(new DownlinkBody.ConfigurationSet("sampling", "r2", Optional.of("r1"), Map.of("interval", new Value.IntegerValue(60))), "thing.config.set"),
            Arguments.of(new DownlinkBody.TimeSyncResponse(0, NOW.toEpochMilli()), "system.time.reply"),
            Arguments.of(upgrade(), "ota.upgrade"), Arguments.of(new DownlinkBody.OtaCancel("t1", "a1"), "ota.cancel"));
    }
    @ParameterizedTest @MethodSource("downlinks")
    void encodesAllDownlinksWithDeadlineAndCorrelation(DownlinkBody body, String method) throws Exception {
        var packet = CODEC.encode(down(body), Transport.MQTT);
        assertEquals(OfficialMqttCodec.DOWNLINK_TOPIC, packet.attributes().get("topic"));
        var root = JSON.readTree(packet.payload().copyBytes());
        assertEquals(method, root.path("method").asText()); assertEquals("1.0", root.path("version").asText());
        assertEquals(NOW.toEpochMilli() + 30000, root.path("expiresAt").longValue()); assertEquals("v1", root.path("modelVersion").asText());
        assertEquals(body instanceof DownlinkBody.TimeSyncResponse, root.has("replyTo"));
        if (body instanceof DownlinkBody.TimeSyncResponse) assertEquals(NOW.toEpochMilli(), root.path("data").path("serverSendTime").longValue());
        if (body instanceof DownlinkBody.OtaUpgrade) { assertEquals("Ed25519", root.path("data").path("package").path("signatureAlgorithm").asText()); assertEquals(1024, root.path("data").path("package").path("size").longValue()); }
    }
    @ParameterizedTest @EnumSource(DownlinkBody.ReportKind.class)
    void encodesExplicitReportAcknowledgement(DownlinkBody.ReportKind kind) throws Exception {
        var root = JSON.readTree(CODEC.encode(down(new DownlinkBody.ReportAcknowledgement(kind)), Transport.MQTT).payload().copyBytes());
        assertTrue(root.path("method").asText().endsWith(".report.reply")); assertEquals(0, root.path("data").size());
        assertEquals("request-1", root.path("replyTo").asText());
    }
    @Test void rejectsSpoofingMalformedAndAmbiguousJson() {
        var good = envelope("thing.property.report", "{\"properties\":{\"heatingLevel\":70}}", "SUCCESS");
        for (var bad : List.of(good.replace("\"1.0\"", "\"2.0\""), good.replace("\"timestamp\":0", "\"timestamp\":-1"),
                good.replace("\"timestamp\":0", "\"timestamp\":1.5"), good.replace("\"timestamp\":0", "\"timestamp\":9223372036854775808"),
                good.replace("\"timestamp\":0", "\"timestamp\":0,\"timestamp\":1"), good.replace("\"version\"", "\"tenantId\":\"other\",\"version\""),
                good.replace("\"heatingLevel\":70", "\"heatingLevel\":null"), good.replace("\"heatingLevel\":70", "\"heatingLevel\":{}"),
                good.replace("\"heatingLevel\":70", "\"heatingLevel\":9223372036854775808"), good + " {}",
                good.replace("thing.property.report", "thing.property.read"), good.replace("\"messageId\":\"m1\"", "\"messageId\":\"\""))) {
            assertThrows(IllegalArgumentException.class, () -> decode(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(new ProtocolPacket(new BinaryPayload(new byte[] {(byte)0xc3, 0x28}), Map.of("topic", OfficialMqttCodec.UPLINK_TOPIC)), CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(new ProtocolPacket(new BinaryPayload(good.getBytes(StandardCharsets.UTF_16)), Map.of("topic", OfficialMqttCodec.UPLINK_TOPIC)), CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(new ProtocolPacket(new BinaryPayload(good.getBytes(StandardCharsets.UTF_8)), Map.of("topic", OfficialMqttCodec.DOWNLINK_TOPIC)), CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> decode(envelope("thing.property.read.reply", "{\"properties\":{}}", "SUCCESS").replace("\"replyTo\":\"request-1\",", "")));
    }
    @Test void rejectsInvalidFirmwareAndFalseCompletion() {
        var signature = Base64.getEncoder().encodeToString(new byte[64]);
        assertThrows(IllegalArgumentException.class, () -> new DownlinkBody.FirmwarePackage(URI.create("http://example.com/a.bin"), 1, "a".repeat(64), "k1", signature));
        assertThrows(IllegalArgumentException.class, () -> new DownlinkBody.FirmwarePackage(URI.create("https://u:p@example.com/a.bin"), 1, "a".repeat(64), "k1", signature));
        assertThrows(IllegalArgumentException.class, () -> new DownlinkBody.FirmwarePackage(URI.create("https://example.com/a.bin"), 1, "bad", "k1", signature));
        assertThrows(IllegalArgumentException.class, () -> new DownlinkBody.FirmwarePackage(URI.create("https://example.com/a.bin"), 1, "a".repeat(64), "k1", "YWJj"));
        var progress = "{\"taskId\":\"t1\",\"attemptId\":\"a1\",\"upgradeMessageId\":\"u1\",\"component\":\"main\",\"targetVersion\":\"2.0\",\"stage\":\"SUCCEEDED\",\"progress\":100,\"installedVersion\":\"1.0\"}";
        assertThrows(IllegalArgumentException.class, () -> decode(envelope("ota.progress.report", progress, "SUCCESS")));
        assertThrows(IllegalArgumentException.class, () -> decode(envelope("ota.progress.report", progress.replace("SUCCEEDED", "FAILED"), "SUCCESS")));
    }
    @Test void timeReplyEchoesRequestAndUsesTrustedReceiveTime() throws Exception {
        var sent = new AtomicReference<DeviceDownlink>();
        var handled = new AtomicReference<DeviceMessage>();
        var handler = new OfficialSystemMessageHandler(message -> { sent.set(message); return CompletableFuture.completedFuture(new DeliveryReceipt(message.messageId(), KEY, NOW)); },
                message -> { handled.set(message); return CompletableFuture.completedFuture(null); }, Clock.fixed(NOW.plusMillis(20), ZoneOffset.UTC));
        var request = CONTEXT.bind(decode(envelope("system.time.request", "{\"clientSendTime\":1234}", "SUCCESS")));
        handler.handle(request).toCompletableFuture().get();
        assertEquals(Optional.of(request.messageId()), sent.get().replyTo()); assertEquals(KEY, sent.get().device());
        var body = assertInstanceOf(DownlinkBody.TimeSyncResponse.class, sent.get().body());
        assertEquals(1234, body.clientSendTime()); assertEquals(NOW.toEpochMilli(), body.serverReceiveTime()); assertNull(handled.get());
        handler.handle(CONTEXT.bind(decode(envelope("thing.property.report", "{\"properties\":{\"heatingLevel\":70}}", "SUCCESS")))).toCompletableFuture().get();
        assertNotNull(handled.get());
    }
    @Test void enforcesPropertyAccessAndValueConstraints() {
        var schema = ValueSchema.of(ValueType.BOOLEAN);
        var model = new DeviceModel(new ProductKey(KEY.tenantId(), new ProductId("warmer")), "v1", Map.of(
                "readable", new PropertyDefinition("readable", schema, PropertyAccess.READ_ONLY),
                "secret", new PropertyDefinition("secret", schema, PropertyAccess.WRITE_ONLY)), Map.of(), Map.of());
        model.validatePropertyRead(List.of("readable")); model.validatePropertyWrite(Map.of("secret", new Value.BooleanValue(true)));
        assertThrows(IllegalArgumentException.class, () -> model.validatePropertyRead(List.of("secret")));
        assertThrows(IllegalArgumentException.class, () -> model.validatePropertyWrite(Map.of("readable", new Value.BooleanValue(true))));
        assertThrows(IllegalArgumentException.class, () -> model.validatePropertyWrite(Map.of("secret", new Value.IntegerValue(1))));
    }
    @Test void enforcesPayloadFieldAndReplyEnvelopeLimits() {
        assertThrows(IllegalArgumentException.class, () -> decode(envelope("thing.property.report", "{\"properties\":{\"text\":\"" + "x".repeat(OfficialMqttCodec.MAX_PAYLOAD_BYTES) + "\"}}", "SUCCESS")));
        assertThrows(IllegalArgumentException.class, () -> CODEC.encode(down(new DownlinkBody.PropertyWrite(Map.of("text", new Value.TextValue("x".repeat(OfficialMqttCodec.MAX_PAYLOAD_BYTES))))), Transport.MQTT));
        var fields = new LinkedHashMap<String, Value>(); for (int i = 0; i < 257; i++) fields.put("p" + i, new Value.BooleanValue(true));
        assertThrows(IllegalArgumentException.class, () -> CODEC.encode(down(new DownlinkBody.PropertyWrite(fields)), Transport.MQTT));
        var now = NOW;
        assertThrows(IllegalArgumentException.class, () -> new DeviceDownlink(new MessageId("x"), KEY, "v1", now, now.plusSeconds(1), Optional.empty(), Optional.empty(), new DownlinkBody.TimeSyncResponse(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new DeviceDownlink(new MessageId("x"), KEY, "v1", now, now.plusSeconds(1), Optional.of(new MessageId("x")), Optional.of(ReplyResult.success()), new DownlinkBody.TimeSyncResponse(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new DownlinkBody.PropertyRead(List.of("same", "same")));
    }
    @Test void reservesProtocolSpiWithoutDynamicPluginLoading() {
        var protocol = new OfficialMqttProtocol(request -> CompletableFuture.completedFuture(new AuthenticationResult.Rejected("TEST")));
        assertEquals("nebula-mqtt-v1", protocol.id()); assertEquals(Set.of(Transport.MQTT), protocol.supportedTransports()); assertNotNull(protocol.authenticator());
    }
    private static DownlinkBody.OtaUpgrade upgrade() {
        return new DownlinkBody.OtaUpgrade("t1", "a1", "main", "1.0", "2.0", "A",
                new DownlinkBody.FirmwarePackage(URI.create("https://firmware.example.com/main-2.bin"), 1024, "a".repeat(64), "firmware-key-1", Base64.getEncoder().encodeToString(new byte[64])));
    }
    private static DeviceDownlink down(DownlinkBody body) {
        boolean reply = body instanceof DownlinkBody.TimeSyncResponse || body instanceof DownlinkBody.ReportAcknowledgement;
        return new DeviceDownlink(new MessageId("down-1"), KEY, "v1", NOW, NOW.plusSeconds(30), reply ? Optional.of(new MessageId("request-1")) : Optional.empty(), reply ? Optional.of(ReplyResult.success()) : Optional.empty(), body);
    }
    private static DecodedMessage decode(String json) { return CODEC.decode(new ProtocolPacket(new BinaryPayload(json.getBytes(StandardCharsets.UTF_8)), Map.of("topic", OfficialMqttCodec.UPLINK_TOPIC)), CONTEXT).getFirst(); }
    private static String envelope(String method, String data, String code) {
        return "{\"version\":\"1.0\",\"messageId\":\"m1\",\"method\":\"" + method + "\",\"timestamp\":0,\"data\":" + data
                + (method.endsWith(".reply") ? ",\"replyTo\":\"request-1\",\"result\":{\"code\":\"" + code + "\"}" : "") + "}";
    }
}
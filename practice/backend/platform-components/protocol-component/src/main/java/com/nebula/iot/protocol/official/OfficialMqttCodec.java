package com.nebula.iot.protocol.official;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.nebula.iot.core.command.*;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.message.DeviceMessageBody.*;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.core.protocol.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

/** 星云默认设备协议1.0；身份只由认证会话注入，协议不得覆盖可信身份。 */
public final class OfficialMqttCodec implements DeviceMessageCodec {
    public static final String UPLINK_TOPIC = "nebula/v1/up";
    public static final String DOWNLINK_TOPIC = "nebula/v1/down";
    public static final int MAX_PAYLOAD_BYTES = 60 * 1024;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(65536).maxNumberLength(128).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public OfficialMqttCodec() { this(Clock.systemUTC()); }
    public OfficialMqttCodec(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    @Override
    public List<DecodedMessage> decode(ProtocolPacket packet, DecodeContext context) {
        if (!Transport.MQTT.equals(context.transport()) || !UPLINK_TOPIC.equals(packet.attributes().get("topic"))) throw invalid("不支持的传输或上行Topic");
        if (packet.payload().size() > MAX_PAYLOAD_BYTES) throw invalid("载荷超过60KiB");
        try {
            // 显式严格解码，禁止JSON解析器自动接受UTF-16及无效UTF-8。
            var json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(packet.payload().copyBytes())).toString();
            var root = mapper.readTree(json);
            fields(root, "version", "messageId", "method", "timestamp", "data", "replyTo", "result");
            if (!"1.0".equals(text(root, "version"))) throw invalid("不支持的协议版本");
            var id = new MessageId(text(root, "messageId"));
            var occurredAt = Instant.ofEpochMilli(number(root, "timestamp"));
            var method = text(root, "method");
            boolean reply = method.endsWith(".reply");
            Optional<MessageId> replyTo = Optional.empty();
            Optional<ReplyResult> result = Optional.empty();
            if (reply) {
                replyTo = Optional.of(new MessageId(text(root, "replyTo")));
                if (replyTo.get().equals(id)) throw invalid("回复必须使用新messageId");
                var r = root.get("result"); fields(r, "code", "message");
                result = Optional.of(new ReplyResult(text(r, "code"), optionalText(r, "message")));
            } else if (root.has("replyTo") || root.has("result")) throw invalid("请求和报告不允许携带回复结果");
            var data = root.get("data");
            if (data == null || !data.isObject()) throw invalid("data必须为对象");
            var body = decodeBody(method, data, result);
            return List.of(new DecodedMessage(id, occurredAt, body, replyTo, result));
        } catch (IOException error) {
            throw new IllegalArgumentException("无法解析UTF-8 JSON设备消息", error);
        }
    }

    private DeviceMessageBody decodeBody(String method, JsonNode d, Optional<ReplyResult> result) {
        RequestKind failureKind = switch (method) {
            case "thing.property.read.reply" -> RequestKind.PROPERTY_READ;
            case "thing.property.write.reply" -> RequestKind.PROPERTY_WRITE;
            case "thing.config.get.reply" -> RequestKind.CONFIGURATION_READ;
            case "thing.config.set.reply" -> RequestKind.CONFIGURATION_SET;
            case "ota.upgrade.reply" -> RequestKind.OTA_UPGRADE;
            case "ota.cancel.reply" -> RequestKind.OTA_CANCEL;
            default -> null;
        };
        if (failureKind != null && !result.orElseThrow().isSuccess()) {
            fields(d); return new RequestFailure(failureKind);
        }
        return switch (method) {
            case "thing.property.report" -> { fields(d, "properties"); var v = values(d.get("properties")); if (v.isEmpty()) throw invalid("属性报告不能为空"); yield new PropertyReport(v); }
            case "thing.property.read.reply" -> { fields(d, "properties"); yield new PropertyReadReply(values(d.get("properties"))); }
            case "thing.property.write.reply" -> { fields(d, "properties"); yield new PropertyWriteReply(values(d.get("properties"))); }
            case "thing.event.report" -> { fields(d, "eventId", "fields"); yield new EventReport(text(d, "eventId"), values(d.get("fields"))); }
            case "thing.command.reply" -> {
                fields(d, "commandId", "status");
                var status = ExecutionStatus.valueOf(text(d, "status"));
                var r = result.orElseThrow();
                if ((status == ExecutionStatus.FAILED) == r.isSuccess()) throw invalid("命令状态与结果码不一致");
                yield new CommandReply(new CommandId(text(d, "commandId")), status, r.isSuccess() ? Optional.empty() : Optional.of(r.code()));
            }
            case "thing.info.report" -> {
                fields(d, "firmwareVersion", "hardwareVersion", "bootId", "sdkVersion", "clockSynced");
                if (!d.path("clockSynced").isBoolean()) throw invalid("clockSynced必须为布尔值");
                yield new DeviceInfoReport(text(d, "firmwareVersion"), text(d, "hardwareVersion"), text(d, "bootId"), optionalText(d, "sdkVersion"), d.get("clockSynced").booleanValue());
            }
            case "thing.config.report" -> { fields(d, "scope", "version", "values"); yield new ConfigurationReport(text(d, "scope"), text(d, "version"), values(d.get("values"))); }
            case "thing.config.get.reply" -> { fields(d, "scope", "version", "values"); yield new ConfigurationReadReply(text(d, "scope"), text(d, "version"), values(d.get("values"))); }
            case "thing.config.set.reply" -> { fields(d, "scope", "version"); yield new ConfigurationSetReply(text(d, "scope"), text(d, "version")); }
            case "system.time.request" -> { fields(d, "clientSendTime"); yield new TimeSyncRequest(number(d, "clientSendTime")); }
            case "ota.version.report" -> {
                fields(d, "versions"); var n = d.get("versions"); if (n == null || !n.isObject()) throw invalid("versions必须为对象");
                var versions = new LinkedHashMap<String, String>(); n.fieldNames().forEachRemaining(key -> { identifier(key); versions.put(key, text(n, key)); });
                yield new OtaVersionReport(versions);
            }
            case "ota.upgrade.reply" -> { fields(d, "taskId", "attemptId"); yield new OtaUpgradeReply(text(d, "taskId"), text(d, "attemptId")); }
            case "ota.cancel.reply" -> { fields(d, "taskId", "attemptId"); yield new OtaCancelReply(text(d, "taskId"), text(d, "attemptId")); }
            case "ota.progress.report" -> {
                fields(d, "taskId", "attemptId", "upgradeMessageId", "component", "targetVersion", "stage", "progress", "installedVersion", "errorCode");
                long progress = number(d, "progress"); if (progress > 100) throw invalid("progress超过100");
                yield new OtaProgressReport(text(d, "taskId"), text(d, "attemptId"), new MessageId(text(d, "upgradeMessageId")),
                        text(d, "component"), text(d, "targetVersion"), OtaStage.valueOf(text(d, "stage")), (int) progress,
                        optionalText(d, "installedVersion"), optionalText(d, "errorCode"));
            }
            default -> throw invalid("不支持的上行method：" + method);
        };
    }

    @Override public ProtocolPacket encode(DeviceCommand command, Transport transport) { return encode(DeviceDownlink.from(command), transport); }

    @Override
    public ProtocolPacket encode(DeviceDownlink message, Transport transport) {
        if (!Transport.MQTT.equals(transport)) throw invalid("协议仅支持MQTT");
        var root = mapper.createObjectNode();
        root.put("version", "1.0"); root.put("messageId", identifier(message.messageId().value()));
        root.put("timestamp", epoch(message.createdAt())); root.put("modelVersion", identifier(message.modelVersion()));
        root.put("expiresAt", epoch(message.expiresAt()));
        message.replyTo().ifPresent(id -> root.put("replyTo", identifier(id.value())));
        message.result().ifPresent(result -> { var r = root.putObject("result"); r.put("code", result.code()); result.message().ifPresent(value -> r.put("message", value)); });
        var d = root.putObject("data");
        String method = switch (message.body()) {
            case DownlinkBody.PropertyRead read -> { var ids = d.putArray("properties"); read.properties().forEach(id -> ids.add(identifier(id))); yield "thing.property.read"; }
            case DownlinkBody.PropertyWrite write -> { putValues(d, "properties", write.properties()); yield "thing.property.write"; }
            case DownlinkBody.OperationInvoke invoke -> {
                d.put("commandId", identifier(invoke.commandId().value())); d.put("operationId", identifier(invoke.operationId())); putValues(d, "inputs", invoke.inputs()); yield "thing.command.invoke";
            }
            case DownlinkBody.ConfigurationRead get -> { d.put("scope", identifier(get.scope())); yield "thing.config.get"; }
            case DownlinkBody.ConfigurationSet set -> {
                d.put("scope", identifier(set.scope())); d.put("version", identifier(set.version())); set.expectedVersion().ifPresent(value -> d.put("expectedVersion", identifier(value))); putValues(d, "values", set.values()); yield "thing.config.set";
            }
            case DownlinkBody.TimeSyncResponse time -> {
                d.put("clientSendTime", time.clientSendTime()); d.put("serverReceiveTime", time.serverReceiveTime()); d.put("serverSendTime", epoch(clock.instant())); yield "system.time.reply";
            }
            case DownlinkBody.OtaUpgrade ota -> {
                d.put("taskId", identifier(ota.taskId())); d.put("attemptId", identifier(ota.attemptId())); d.put("component", identifier(ota.component()));
                d.put("fromVersion", identifier(ota.fromVersion())); d.put("targetVersion", identifier(ota.targetVersion())); d.put("hardwareVersion", identifier(ota.hardwareVersion()));
                var f = ota.firmware(); var p = d.putObject("package"); p.put("url", f.url().toASCIIString()); p.put("size", f.size()); p.put("sha256", f.sha256());
                p.put("signatureAlgorithm", "Ed25519"); p.put("keyId", identifier(f.keyId())); p.put("signature", f.signature()); yield "ota.upgrade";
            }
            case DownlinkBody.OtaCancel cancel -> { d.put("taskId", identifier(cancel.taskId())); d.put("attemptId", identifier(cancel.attemptId())); yield "ota.cancel"; }
            case DownlinkBody.ReportAcknowledgement ack -> switch (ack.kind()) {
                case PROPERTY -> "thing.property.report.reply";
                case EVENT -> "thing.event.report.reply";
                case INFO -> "thing.info.report.reply";
                case CONFIGURATION -> "thing.config.report.reply";
                case OTA_VERSION -> "ota.version.report.reply";
                case OTA_PROGRESS -> "ota.progress.report.reply";
            };
        };
        root.put("method", method);
        try {
            var bytes = mapper.writeValueAsBytes(root); if (bytes.length > MAX_PAYLOAD_BYTES) throw invalid("载荷超过60KiB");
            return new ProtocolPacket(new BinaryPayload(bytes), Map.of("topic", DOWNLINK_TOPIC));
        } catch (IOException error) { throw new IllegalArgumentException("无法编码下行消息", error); }
    }

    private static void fields(JsonNode object, String... allowed) {
        if (object == null || !object.isObject()) throw invalid("必须为JSON对象");
        var names = Set.of(allowed);
        object.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) throw invalid("未定义字段：" + name); });
    }
    private static String text(JsonNode object, String name) {
        var value = object.get(name);
        if (value == null || !value.isTextual()) throw invalid("缺少文本字段：" + name);
        // result.message是人类可读说明，其余文本字段为短标识。
        if (name.equals("message")) {
            if (value.textValue().isBlank() || value.textValue().codePointCount(0, value.textValue().length()) > 512) throw invalid("结果说明过长或为空");
            return value.textValue();
        }
        return identifier(value.textValue());
    }
    private static String identifier(String value) {
        if (value.isBlank() || value.codePointCount(0, value.length()) > 128 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("标识必须为1至128个非控制字符");
        return value;
    }
    private static Optional<String> optionalText(JsonNode object, String name) { return object.has(name) ? Optional.of(text(object, name)) : Optional.empty(); }
    private static long number(JsonNode object, String name) {
        var value = object.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid("必须为非负64位整数：" + name);
        return value.longValue();
    }
    private static long epoch(Instant time) { long value = time.toEpochMilli(); if (value < 0) throw invalid("时间不能为负数"); return value; }
    private static Map<String, Value> values(JsonNode object) {
        if (object == null || !object.isObject() || object.size() > 256) throw invalid("字段必须为最多256项的对象");
        var result = new LinkedHashMap<String, Value>();
        object.fields().forEachRemaining(entry -> {
            identifier(entry.getKey()); var value = entry.getValue();
            Value scalar;
            if (value.isBoolean()) scalar = new Value.BooleanValue(value.booleanValue());
            else if (value.isIntegralNumber() && value.canConvertToLong()) scalar = new Value.IntegerValue(value.longValue());
            else if (value.isFloatingPointNumber()) scalar = new Value.DecimalValue(value.decimalValue());
            else if (value.isTextual()) scalar = new Value.TextValue(value.textValue());
            else throw invalid("字段仅支持布尔、64位整数、小数和文本");
            result.put(entry.getKey(), scalar);
        }); return result;
    }
    private void putValues(ObjectNode parent, String name, Map<String, Value> values) {
        if (values.size() > 256) throw invalid("字段数量超过256");
        var object = parent.putObject(name);
        values.forEach((key, value) -> object.set(identifier(key), switch (value) {
            case Value.BooleanValue bool -> BooleanNode.valueOf(bool.value());
            case Value.IntegerValue integer -> LongNode.valueOf(integer.value());
            case Value.DecimalValue decimal -> DecimalNode.valueOf(decimal.value());
            case Value.TextValue text -> TextNode.valueOf(text.value());
        }));
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
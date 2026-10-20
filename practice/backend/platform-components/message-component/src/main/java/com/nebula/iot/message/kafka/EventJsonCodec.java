package com.nebula.iot.message.kafka;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nebula.iot.core.message.DeviceMessageBody;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.message.EventMessage;
import java.util.*;

/** Versioned JSON with explicit type aliases. Never enables Java class-name deserialization. */
public final class EventJsonCodec {
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, Class<?>> types = Map.ofEntries(
            Map.entry("property-report", DeviceMessageBody.PropertyReport.class),
            Map.entry("property-read-reply", DeviceMessageBody.PropertyReadReply.class),
            Map.entry("property-write-reply", DeviceMessageBody.PropertyWriteReply.class),
            Map.entry("event-report", DeviceMessageBody.EventReport.class),
            Map.entry("command-reply", DeviceMessageBody.CommandReply.class),
            Map.entry("device-info-report", DeviceMessageBody.DeviceInfoReport.class),
            Map.entry("configuration-report", DeviceMessageBody.ConfigurationReport.class),
            Map.entry("configuration-read-reply", DeviceMessageBody.ConfigurationReadReply.class),
            Map.entry("configuration-set-reply", DeviceMessageBody.ConfigurationSetReply.class),
            Map.entry("time-sync-request", DeviceMessageBody.TimeSyncRequest.class),
            Map.entry("ota-version-report", DeviceMessageBody.OtaVersionReport.class),
            Map.entry("ota-upgrade-reply", DeviceMessageBody.OtaUpgradeReply.class),
            Map.entry("ota-cancel-reply", DeviceMessageBody.OtaCancelReply.class),
            Map.entry("ota-progress-report", DeviceMessageBody.OtaProgressReport.class),
            Map.entry("request-failure", DeviceMessageBody.RequestFailure.class),
            Map.entry("connection-changed", DeviceMessageBody.ConnectionChanged.class));

    public EventJsonCodec() {
        json.addMixIn(Value.class, ValueType.class);
        json.registerSubtypes(new NamedType(Value.BooleanValue.class, "boolean"),
                new NamedType(Value.IntegerValue.class, "integer"),
                new NamedType(Value.DecimalValue.class, "decimal"),
                new NamedType(Value.TextValue.class, "text"), new NamedType(Value.ArrayValue.class, "array"));
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "valueType")
    private interface ValueType { }

    public byte[] encode(EventMessage event) {
        try {
            String type = types.entrySet().stream().filter(e -> e.getValue().isInstance(event.payload()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (type == null) {
                if (!(event.payload() instanceof Map<?, ?>))
                    throw new IllegalArgumentException("Cluster payload requires a registered event type or JSON object");
                validateJson(event.payload());
                type = "json";
            }
            // Writing the typed record separately preserves Value's polymorphic generic metadata.
            var root = json.createObjectNode();
            root.put("schemaVersion", 1);
            root.put("payloadType", type);
            root.set("event", json.valueToTree(event));
            root.withObject("/event").set("payload", json.valueToTree(event.payload()));
            return json.writeValueAsBytes(root);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot encode event", e);
        }
    }

    public EventMessage decode(byte[] bytes) {
        try {
            var root = json.readTree(bytes);
            if (root.path("schemaVersion").asInt(-1) != 1) throw new IllegalArgumentException("Unsupported event schema");
            String type = root.path("payloadType").asText();
            Class<?> target = "json".equals(type) ? Map.class : types.get(type);
            if (target == null) throw new IllegalArgumentException("Unknown event payload type: " + type);
            EventMessage envelope = json.treeToValue(root.required("event"), EventMessage.class);
            Object payload = json.treeToValue(root.required("event").required("payload"), target);
            return new EventMessage(envelope.eventId(), envelope.topic(), envelope.occurredAt(), envelope.receivedAt(),
                    envelope.tenantId(), envelope.deviceId(), envelope.modelVersion(), envelope.messageId(),
                    envelope.source(), envelope.headers(), payload, envelope.productId());
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot decode event", e);
        }
    }

    private static void validateJson(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return;
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (!(k instanceof String)) throw new IllegalArgumentException("JSON keys must be strings");
                validateJson(v);
            });
        } else if (value instanceof List<?> list) list.forEach(EventJsonCodec::validateJson);
        else throw new IllegalArgumentException("Unregistered nested payload type: " + value.getClass().getName());
    }
}
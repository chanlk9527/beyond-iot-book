package com.nebula.iot.message;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable platform event envelope shared by local and future clustered buses. */
public record EventMessage(String eventId, String topic, Instant occurredAt, Instant receivedAt,
                           String tenantId, String deviceId, String modelVersion, String messageId,
                           String source, Map<String, String> headers, Object payload) {
    public EventMessage {
        eventId = required(eventId, "eventId"); topic = TopicPattern.validate(topic);
        Objects.requireNonNull(occurredAt, "occurredAt"); Objects.requireNonNull(receivedAt, "receivedAt");
        tenantId = required(tenantId, "tenantId"); deviceId = required(deviceId, "deviceId");
        modelVersion = required(modelVersion, "modelVersion"); messageId = required(messageId, "messageId");
        source = required(source, "source"); headers = headers == null ? Map.of() : Map.copyOf(headers);
        Objects.requireNonNull(payload, "payload");
    }
    public static EventMessage of(String topic, Object payload) {
        var now = Instant.now(); var id = UUID.randomUUID().toString();
        return new EventMessage(id, topic, now, now, "local", "unknown", "unknown", id, "APPLICATION", Map.of(), payload);
    }
    private static String required(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); return value; }
}
package com.nebula.iot.core.protocol;

import java.util.Map;
import java.util.Objects;

/** Protocol attributes (such as MQTT topic) are not trusted identity or business authorization. */
public record ProtocolPacket(BinaryPayload payload, Map<String, String> attributes) {
    public ProtocolPacket {
        Objects.requireNonNull(payload, "payload");
        attributes = Map.copyOf(attributes);
    }

    @Override
    public String toString() {
        return "ProtocolPacket[payloadSize=" + payload.size() + ", attributeCount=" + attributes.size() + "]";
    }
}

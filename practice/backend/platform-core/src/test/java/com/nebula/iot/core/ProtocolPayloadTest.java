package com.nebula.iot.core;

import com.nebula.iot.core.protocol.*;
import java.nio.ReadOnlyBufferException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolPayloadTest {
    @Test
    void payloadCannotBeMutatedViaInputArrayOutputArrayOrBuffer() {
        var input = new byte[] {1, 2, 3};
        var payload = new BinaryPayload(input);
        input[0] = 99;
        var output = payload.copyBytes();
        output[1] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, payload.copyBytes());
        assertThrows(ReadOnlyBufferException.class, () -> payload.asReadOnlyBuffer().put(0, (byte) 99));
        assertEquals(new BinaryPayload(new byte[] {1, 2, 3}), payload);
        assertEquals(new BinaryPayload(new byte[] {1, 2, 3}).hashCode(), payload.hashCode());
        assertEquals("BinaryPayload[size=3]", payload.toString());
    }

    @Test
    void protocolAttributesAreImmutableSnapshots() {
        var attributes = new HashMap<>(Map.of("topic", "devices/warmer-1"));
        var packet = new ProtocolPacket(new BinaryPayload(new byte[0]), attributes);
        attributes.clear();
        assertEquals("devices/warmer-1", packet.attributes().get("topic"));
        assertThrows(UnsupportedOperationException.class, () -> packet.attributes().clear());
    }

    @Test
    void packetLoggingDoesNotExposePayloadOrAttributeValues() {
        var packet = new ProtocolPacket(new BinaryPayload(new byte[] {42}), Map.of("authorization", "secret"));
        assertEquals("ProtocolPacket[payloadSize=1, attributeCount=1]", packet.toString());
        assertFalse(packet.toString().contains("secret"));
    }

    @Test
    void transportIdentifierIsNormalizedAndExtensible() {
        assertEquals(Transport.MQTT, new Transport("MQTT"));
        assertEquals("custom-bus", new Transport("custom-bus").id());
        assertThrows(IllegalArgumentException.class, () -> new Transport("mqtt/topic"));
    }
}

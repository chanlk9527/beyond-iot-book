package com.nebula.iot.core;

import com.nebula.iot.core.command.*;
import com.nebula.iot.core.device.*;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.core.protocol.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageAndCommandTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final DeviceKey DEVICE = new DeviceKey(new TenantId("tenant-a"), new DeviceId("warmer-1"));

    @Test
    void expiryBoundaryIsExclusiveAndInvalidLifetimeIsRejected() {
        var command = new DeviceCommand(new CommandId("c1"), DEVICE, "v1", "setHeatingLevel",
                Map.of("heatingLevel", new Value.IntegerValue(70)), NOW, NOW.plusSeconds(10));
        assertFalse(command.isExpiredAt(NOW.plusSeconds(10).minusNanos(1)));
        assertTrue(command.isExpiredAt(NOW.plusSeconds(10)));
        assertTrue(command.isExpiredAt(NOW.plusSeconds(20)));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCommand(command.commandId(), DEVICE,
                "v1", "setHeatingLevel", command.inputs(), NOW, NOW));
    }

    @Test
    void payloadAndCommandMapsCannotBeChangedAfterConstruction() {
        var fields = new HashMap<String, Value>();
        fields.put("heatingLevel", new Value.IntegerValue(70));
        var report = new DeviceMessageBody.PropertyReport(fields);
        var command = new DeviceCommand(new CommandId("c1"), DEVICE, "v1", "setHeatingLevel", fields, NOW, NOW.plusSeconds(10));
        fields.put("heatingLevel", new Value.IntegerValue(30));
        assertEquals(new Value.IntegerValue(70), report.properties().get("heatingLevel"));
        assertEquals(new Value.IntegerValue(70), command.inputs().get("heatingLevel"));
        assertThrows(UnsupportedOperationException.class, () -> report.properties().clear());
        assertThrows(UnsupportedOperationException.class, () -> command.inputs().clear());
    }

    @Test
    void authenticatedContextOwnsIdentityAndPlatformReceiveTime() {
        var context = new DecodeContext(DEVICE, Transport.MQTT, "v1", NOW);
        var decoded = new DecodedMessage(new MessageId("m1"), NOW.plusSeconds(60),
                new DeviceMessageBody.PropertyReport(Map.of("heatingLevel", new Value.IntegerValue(70))));
        var message = context.bind(decoded);
        assertEquals(DEVICE, message.device());
        assertEquals(NOW, message.receivedAt());
        assertEquals(NOW.plusSeconds(60), message.occurredAt());
        assertEquals("v1", message.modelVersion());
        assertEquals(MessageOrigin.DEVICE, message.origin());
    }

    @Test
    void deviceCodecCannotClaimGatewayConnectionEvents() {
        var body = new DeviceMessageBody.ConnectionChanged(ConnectionState.ONLINE);
        assertThrows(IllegalArgumentException.class, () -> new DecodedMessage(new MessageId("m1"), NOW, body));
        assertThrows(IllegalArgumentException.class, () -> new DeviceMessage(new MessageId("m1"), DEVICE,
                "v1", NOW, NOW, MessageOrigin.DEVICE, body));
        assertDoesNotThrow(() -> new DeviceMessage(new MessageId("m1"), DEVICE,
                "v1", NOW, NOW, MessageOrigin.GATEWAY, body));
    }

    @Test
    void deviceFailureRequiresCodeAndSuccessfulReplyCannotCarryFailure() {
        var command = new CommandId("c1");
        assertDoesNotThrow(() -> new DeviceMessageBody.CommandReply(command, ExecutionStatus.FAILED, Optional.of("INVALID_VALUE")));
        assertDoesNotThrow(() -> new DeviceMessageBody.CommandReply(command, ExecutionStatus.SUCCEEDED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new DeviceMessageBody.CommandReply(command, ExecutionStatus.FAILED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new DeviceMessageBody.CommandReply(command, ExecutionStatus.SUCCEEDED, Optional.of("ERROR")));
    }
}

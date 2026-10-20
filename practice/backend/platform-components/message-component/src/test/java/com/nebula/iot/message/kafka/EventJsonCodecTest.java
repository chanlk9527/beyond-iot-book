package com.nebula.iot.message.kafka;

import com.nebula.iot.core.message.DeviceMessageBody;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.message.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventJsonCodecTest {
    private final EventJsonCodec codec = new EventJsonCodec();

    @Test void preservesEnvelopeAndTypedDeviceValues() {
        var values = Map.<String, Value>of("bool", new Value.BooleanValue(true),
                "int", new Value.IntegerValue(Long.MAX_VALUE), "decimal", new Value.DecimalValue(new BigDecimal("25.1234567890123456789")),
                "text", new Value.TextValue("中文"), "array", new Value.ArrayValue(List.of(new Value.IntegerValue(1), new Value.IntegerValue(2))));
        var now = Instant.now();
        var event = new EventMessage("e1", "device/d001/property/report", now, now,
                "tenant-a", "d001", "v2", "m1", "DEVICE", Map.of("traceId", "t1"),
                new DeviceMessageBody.PropertyReport(values));
        assertEquals(event, codec.decode(codec.encode(event)));
        var reportedEvent=EventMessage.of("device/d001/event/alarm",new DeviceMessageBody.EventReport("alarm",values));
        assertEquals(reportedEvent,codec.decode(codec.encode(reportedEvent)));
    }

    @Test void supportsJsonAndOptionalFieldsWithoutClassNames() {
        var info = EventMessage.of("device/d/info/report", new DeviceMessageBody.DeviceInfoReport(
                "1", "1", "boot", Optional.of("sdk"), true));
        assertEquals(info, codec.decode(codec.encode(info)));
        var event = EventMessage.of("external/device/shadow", Map.of("version", 4, "kind", "shadow"));
        assertEquals(event, codec.decode(codec.encode(event)));
        String wire = new String(codec.encode(info), StandardCharsets.UTF_8);
        assertFalse(wire.contains("com.nebula"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                wire.replace("device-info-report", "java.lang.Runtime").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                wire.replace("\"schemaVersion\":1", "\"schemaVersion\":2").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(EventMessage.of("a/b", new Object())));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(EventMessage.of("a/b", Map.of("nested", new Object()))));
    }

    @Test void patternsKeepLocalSemanticsAndOverlapsInvokeOnce() {
        var bus = new LocalEventBus(Runnable::run, java.time.Duration.ofSeconds(1));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var spec = SubscriptionSpec.shared("selected", "device/d001/**", "device/*/property/report");
        try (var subscription = bus.subscribe(spec, e -> {
            calls.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(null);
        })) {
            bus.publish("device/d001/property/report", Map.of()).toCompletableFuture().join();
            bus.publish("device/d002/property/report", Map.of()).toCompletableFuture().join();
            bus.publish("device/d002/event/alarm", Map.of()).toCompletableFuture().join();
            assertEquals(2, calls.get());
        }
        assertFalse(spec.matches("device/d002/property/report/extra"));
        assertThrows(IllegalArgumentException.class, () -> SubscriptionSpec.shared("bad", "device/**/report"));
    }

    @Test void allCurrentDeviceBodiesCanCrossTheWire() {
        var values = Map.<String, Value>of("temperature", new Value.IntegerValue(40));
        var bodies = List.of(
                new DeviceMessageBody.PropertyReport(values),
                new DeviceMessageBody.PropertyReadReply(values),
                new DeviceMessageBody.PropertyWriteReply(values),
                new DeviceMessageBody.EventReport("alarm", values),
                new DeviceMessageBody.CommandReply(new com.nebula.iot.core.identity.CommandId("c1"),
                        com.nebula.iot.core.command.ExecutionStatus.FAILED, Optional.of("E1")),
                new DeviceMessageBody.DeviceInfoReport("v1", "h1", "b1", Optional.empty(), false),
                new DeviceMessageBody.ConfigurationReport("system", "v1", values),
                new DeviceMessageBody.ConfigurationReadReply("system", "v1", values),
                new DeviceMessageBody.ConfigurationSetReply("system", "v1"),
                new DeviceMessageBody.TimeSyncRequest(100),
                new DeviceMessageBody.OtaVersionReport(Map.of("main", "v1")),
                new DeviceMessageBody.OtaUpgradeReply("task", "attempt"),
                new DeviceMessageBody.OtaCancelReply("task", "attempt"),
                new DeviceMessageBody.OtaProgressReport("task", "attempt", new com.nebula.iot.core.identity.MessageId("m1"),
                        "main", "v2", DeviceMessageBody.OtaStage.DOWNLOADING, 20, Optional.empty(), Optional.empty()),
                new DeviceMessageBody.RequestFailure(DeviceMessageBody.RequestKind.OTA_UPGRADE),
                new DeviceMessageBody.ConnectionChanged(com.nebula.iot.core.device.ConnectionState.ONLINE));
        for (var body : bodies) {
            var event = EventMessage.of("device/d/test", body);
            assertEquals(event, codec.decode(codec.encode(event)), body.getClass().getSimpleName());
        }
    }
}
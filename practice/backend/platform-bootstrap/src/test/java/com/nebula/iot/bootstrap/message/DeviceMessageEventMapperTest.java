package com.nebula.iot.bootstrap.message;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.DeviceMessageBody;
import com.nebula.iot.core.protocol.*;
import com.nebula.iot.message.LocalEventBus;
import com.nebula.iot.message.SubscriptionSpec;
import com.nebula.iot.message.routing.EventSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceMessageEventMapperTest {
    @Test void authenticatedProductFlowsFromIngressIntoProductSubscription() {
        var device = new DeviceKey(new TenantId("t1"), new DeviceId("d1"));
        var now = Instant.now();
        var decoded = new DecodedMessage(new MessageId("m1"), now, new DeviceMessageBody.PropertyReport(Map.of()));
        var message = new DecodeContext(device, Transport.MQTT, "v1", now, new ProductId("p1")).bind(decoded);
        var event = new DeviceMessageEventMapper().map(message);
        assertEquals("p1", event.productId());
        assertEquals("device/d1/property/report", event.topic());
        var received = new ArrayList<String>();
        var bus = new LocalEventBus(Runnable::run, Duration.ofSeconds(1));
        try (var subscription = bus.subscribe(SubscriptionSpec.shared("product", "device/**"),
                EventSelector.product("t1", "p1"), e -> {
                    received.add(e.productId()); return CompletableFuture.completedFuture(null);
                })) {
            new EventPublishingDeviceMessageHandler(m -> CompletableFuture.completedFuture(null), bus)
                    .handle(message).toCompletableFuture().join();
            assertEquals(List.of("p1"), received);
        }
    }
}

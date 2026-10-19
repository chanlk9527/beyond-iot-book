package com.nebula.iot.bootstrap.subscription;

import com.nebula.iot.device.api.DeviceStateChanged;
import com.nebula.iot.message.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class StateEventBridge {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StateEventBridge.class);
    private final EventBus bus;
    public StateEventBridge(EventBus bus) { this.bus = bus; }
    @EventListener public void changed(DeviceStateChanged change) {
        var now = Instant.now(); var id = UUID.randomUUID().toString();
        bus.publish(new EventMessage(id, "external/device/shadow", now, now,
                change.device().tenantId().value(), change.device().deviceId().value(), change.modelVersion(),
                id, "APPLICATION", Map.of(), Map.of("kind", "shadow", "version", change.version()))).whenComplete((result, error) -> {
                    if (error != null) log.error("Failed to publish shadow notification {}", id, error);
                });
    }
}

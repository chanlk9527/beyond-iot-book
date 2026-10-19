package com.nebula.iot.bootstrap.subscription;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.message.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Current-process session projection. Offline devices need no retained entry. */
@Component
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class DevicePresence {
    private final Set<DeviceKey> online = ConcurrentHashMap.newKeySet();
    private final EventBus bus;
    public DevicePresence(EventBus bus) { this.bus = bus; }
    public boolean online(DeviceKey key) { return online.contains(key); }
    public void changed(DeviceKey key, boolean connected) {
        if (connected) online.add(key); else online.remove(key);
        var now = Instant.now(); var id = UUID.randomUUID().toString();
        bus.publish(new EventMessage(id, "external/device/presence", now, now,
                key.tenantId().value(), key.deviceId().value(), "unknown", id, "GATEWAY", Map.of(), Map.of("kind", "presence")));
    }
}

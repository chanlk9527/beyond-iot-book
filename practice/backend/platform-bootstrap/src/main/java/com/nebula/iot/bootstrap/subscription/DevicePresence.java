package com.nebula.iot.bootstrap.subscription;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.message.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Queries shared ownership when configured, otherwise the current-process session projection. */
@Component
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class DevicePresence {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DevicePresence.class);
    private final Set<DeviceKey> online = ConcurrentHashMap.newKeySet();
    private final EventBus bus;
    public DevicePresence(EventBus bus) { this.bus = bus; }
    private volatile com.nebula.iot.gateway.JdbcDeviceSessionDirectory directory;
    public void setDirectory(com.nebula.iot.gateway.JdbcDeviceSessionDirectory value) { directory=value; }
    public boolean hasSharedDirectory() { return directory != null; }
    public boolean online(DeviceKey key) { var shared=directory; return shared==null?online.contains(key):shared.owner(key)!=null; }
    public void changed(DeviceKey key, boolean connected) {
        if (connected) online.add(key); else online.remove(key);
        var now = Instant.now(); var id = UUID.randomUUID().toString();
        bus.publish(new EventMessage(id, "external/device/presence", now, now,
                key.tenantId().value(), key.deviceId().value(), "unknown", id, "GATEWAY", Map.of(), Map.of("kind", "presence"))).whenComplete((result, error) -> {
                    if (error != null) log.error("Failed to publish presence notification {}", id, error);
                });
    }
}

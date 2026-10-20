package com.nebula.iot.device.api;

import com.nebula.iot.core.device.*;
import com.nebula.iot.core.model.DeviceModel;
import java.util.Optional;

/** Registered device access, independent of the network transport implementation. */
public interface DeviceConnectionDirectory {
    Optional<DeviceKey> authenticate(String clientId, String username, byte[] password);
    Optional<Access> resolve(DeviceKey key);
    default Optional<Access> resolveChild(DeviceKey gateway,DeviceKey child) { return Optional.empty(); }
    record Access(DeviceDescriptor device, DeviceModel model) {}
    record Revoked(DeviceKey device) {}
    /** Published after a write so every node can evict its local/shared snapshot. */
    record CacheInvalidated(String deviceId, boolean all) {}
}
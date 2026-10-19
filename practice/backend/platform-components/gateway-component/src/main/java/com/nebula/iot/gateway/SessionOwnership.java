package com.nebula.iot.gateway;

import com.nebula.iot.core.device.DeviceKey;

/** Synchronous directory port, invoked exclusively on bounded gateway workers, never an event loop. */
public interface SessionOwnership {
    record Lease(String sessionId, long epoch, long validForMillis) {}
    Lease acquire(DeviceKey device, String clientId);
    Lease renew(DeviceKey device, Lease lease);
    void release(DeviceKey device, Lease lease);
}

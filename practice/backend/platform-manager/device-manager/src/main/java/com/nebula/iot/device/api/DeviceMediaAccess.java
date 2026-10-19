package com.nebula.iot.device.api;

/** Revalidates account, permission, actual device scope, product and lifecycle. */
public interface DeviceMediaAccess {
    record Target(String scope, String deviceId) {}
    Target requireWatch(String actor, String tenantId, String deviceId);
}

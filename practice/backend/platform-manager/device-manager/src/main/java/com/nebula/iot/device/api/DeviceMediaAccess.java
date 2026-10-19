package com.nebula.iot.device.api;

/** Revalidates account, permission, actual device scope, product and lifecycle. */
public interface DeviceMediaAccess {
    record Target(String scope, String deviceId) {}
    Target requireWatch(String actor, String tenantId, String deviceId);

    /**
     * Validate the authoritative device state for a trusted App Server claim.
     * The caller has already authenticated the end user and checked the binding.
     */
    Target requireActive(String tenantId, String deviceId);
}

package com.nebula.iot.core.device;

import com.nebula.iot.core.identity.DeviceId;
import com.nebula.iot.core.identity.TenantId;
import java.util.Objects;

/** Device IDs are scoped by tenant; authorization is enforced by the calling use case. */
public record DeviceKey(TenantId tenantId, DeviceId deviceId) {
    public DeviceKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deviceId, "deviceId");
    }
}

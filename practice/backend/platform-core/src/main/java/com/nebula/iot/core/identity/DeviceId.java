package com.nebula.iot.core.identity;

import com.nebula.iot.core.internal.Checks;

/** Opaque identifier. Construction does not authenticate or authorize its holder. */
public record DeviceId(String value) {
    public DeviceId {
        value = Checks.nonBlank(value, "DeviceId");
    }
}

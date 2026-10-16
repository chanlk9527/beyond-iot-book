package com.nebula.iot.core.device;

import com.nebula.iot.core.internal.Checks;
import java.util.Objects;

/** Minimal device facts, without credentials, mutable state or persistence annotations. */
public record DeviceDescriptor(DeviceKey key, ProductKey product, String modelVersion,
                               DeviceLifecycle lifecycle) {
    public DeviceDescriptor {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(lifecycle, "lifecycle");
        modelVersion = Checks.nonBlank(modelVersion, "modelVersion");
        if (!key.tenantId().equals(product.tenantId())) {
            throw new IllegalArgumentException("Device and product must belong to the same tenant");
        }
    }
}

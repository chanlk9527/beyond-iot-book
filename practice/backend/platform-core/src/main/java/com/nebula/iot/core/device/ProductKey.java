package com.nebula.iot.core.device;

import com.nebula.iot.core.identity.ProductId;
import com.nebula.iot.core.identity.TenantId;
import java.util.Objects;

public record ProductKey(TenantId tenantId, ProductId productId) {
    public ProductKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(productId, "productId");
    }
}

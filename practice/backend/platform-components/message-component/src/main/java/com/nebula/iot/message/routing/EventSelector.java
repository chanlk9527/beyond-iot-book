package com.nebula.iot.message.routing;

import com.nebula.iot.message.EventMessage;
import java.util.Set;

/** Trusted envelope selectors, intersected as tenant -> product -> devices. */
public record EventSelector(String tenantId, String productId, Set<String> deviceIds) {
    /** Existing tenant/device subscriptions keep their original scope. */
    public EventSelector(String tenantId, Set<String> deviceIds) { this(tenantId, null, deviceIds); }

    public EventSelector {
        deviceIds = Set.copyOf(deviceIds);
        if (tenantId != null && tenantId.isBlank()) throw new IllegalArgumentException("Blank tenant");
        if (productId != null && productId.isBlank()) throw new IllegalArgumentException("Blank product");
        if (deviceIds.size() > 100_000 || deviceIds.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Invalid devices");
        if ((productId != null || !deviceIds.isEmpty()) && tenantId == null)
            throw new IllegalArgumentException("Product/device selection requires a tenant");
    }
    public static EventSelector all() { return new EventSelector(null, null, Set.of()); }
    /** Matches every current or future device in this tenant's product. */
    public static EventSelector product(String tenantId, String productId) {
        return new EventSelector(java.util.Objects.requireNonNull(tenantId, "tenantId"),
                java.util.Objects.requireNonNull(productId, "productId"), Set.of());
    }
    public boolean matches(EventMessage event) {
        return (tenantId == null || tenantId.equals(event.tenantId()))
                && (productId == null || productId.equals(event.productId()))
                && (deviceIds.isEmpty() || deviceIds.contains(event.deviceId()));
    }
}

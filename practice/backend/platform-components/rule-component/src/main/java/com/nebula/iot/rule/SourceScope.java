package com.nebula.iot.rule;

import java.util.Set;

/** Routing scope, not an authorization decision. EventSource must come from trusted metadata. */
public record SourceScope(String tenantId, Set<String> products, Set<String> devices, Set<String> messageTypes) {
    public SourceScope {
        JsonValues.identifier(tenantId);
        products = identifiers(products); devices = identifiers(devices); messageTypes = identifiers(messageTypes);
        if (products.isEmpty() && devices.isEmpty()) throw new IllegalArgumentException("An explicit resource scope is required");
        if (messageTypes.isEmpty()) throw new IllegalArgumentException("Message types are required");
    }
    private static Set<String> identifiers(Set<String> values) {
        values = Set.copyOf(values);
        for (var value : values) JsonValues.identifier(value);
        return values;
    }
    public boolean matches(EventSource source) {
        return tenantId.equals(source.tenantId()) && messageTypes.contains(source.messageType())
                && (products.isEmpty() || products.contains(source.productId()))
                && (devices.isEmpty() || devices.contains(source.deviceId()));
    }
    public record EventSource(String tenantId, String productId, String deviceId, String messageType) {
        public EventSource {
            JsonValues.identifier(tenantId); JsonValues.identifier(productId);
            JsonValues.identifier(deviceId); JsonValues.identifier(messageType);
        }
    }
}

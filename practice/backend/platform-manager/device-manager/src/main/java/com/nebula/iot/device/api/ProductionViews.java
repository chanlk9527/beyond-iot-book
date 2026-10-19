package com.nebula.iot.device.api;

import java.util.List;

public final class ProductionViews {
    private ProductionViews() { }
    public record Batch(String id, String tenantId, String scopeKey, String code, String name,
            String productId, String productName, String modelVersion, int quantity,
            String hardwareVersion, String firmwareVersion, String status, long revision,
            String recipient, String deliveryRef, long createdAt, Long deliveredAt) { }
    public record Item(String deviceId, String code, String name, int sequenceNumber, String lifecycle,
            long credentialVersion, Long burnedVersion, Long testedVersion, String testResult) { }
    public record Event(String id, String deviceId, String actor, String action, String station,
            String result, String note, Long credentialVersion, long occurredAt) { }
    public record Detail(Batch batch, List<Item> items, List<Event> events, long totalEvents) { }
    public record ProvisioningCredential(String deviceId, String code, String clientId, String username,
            String secret, long credentialVersion) {
        @Override public String toString() { return "ProvisioningCredential[redacted]"; }
    }
}

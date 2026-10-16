package com.nebula.iot.device.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/** Latest observations and requested targets; delta is not an execution receipt. */
public record DeviceShadow(long version, String modelVersion, Map<String, ReportedValue> reported,
                           Map<String, DesiredValue> desired, Map<String, JsonNode> delta, Instant updatedAt) {
    public record ReportedValue(JsonNode value, Instant observedAt, Instant receivedAt, String messageId) {}
    public record DesiredValue(JsonNode value, Instant updatedAt) {}
}


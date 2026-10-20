package com.nebula.iot.device.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.nebula.iot.device.api.DeviceShadow;
import com.nebula.iot.device.api.DeviceShadow.*;
import java.time.Instant;
import java.util.*;

/** Immutable updates are committed with a Redis compare-and-set. */
public record ShadowDocument(long version, String modelVersion, Map<String, ReportedValue> reported,
                             Map<String, DesiredValue> desired, Instant updatedAt, List<String> recentMessages) {
    public ShadowDocument {
        reported = Map.copyOf(reported); desired = Map.copyOf(desired); recentMessages = List.copyOf(recentMessages);
    }
    public static ShadowDocument empty(String modelVersion) {
        return new ShadowDocument(0, modelVersion, Map.of(), Map.of(), null, List.of());
    }
    public ShadowDocument withDesired(Map<String, JsonNode> patch, Instant now) {
        var next = new HashMap<>(desired);
        patch.forEach((id, value) -> {
            if (value == null || value.isNull()) next.remove(id);
            else next.put(id, new DesiredValue(value.deepCopy(), now));
        });
        return new ShadowDocument(version + 1, modelVersion, reported, next, now, recentMessages);
    }
    public ShadowDocument withReport(Map<String, JsonNode> values, String messageId, Instant observedAt, Instant receivedAt) {
        if (recentMessages.contains(messageId)) return this;
        var next = new HashMap<>(reported);
        values.forEach((id, value) -> {
            var previous = next.get(id);
            // Device occurrence time orders each property. For equal times use a stable ID tie-break,
            // so delivery/retry order cannot change the final observation.
            if (previous == null || observedAt.isAfter(previous.observedAt()) ||
                    (observedAt.equals(previous.observedAt()) && messageId.compareTo(previous.messageId()) > 0))
                next.put(id, new ReportedValue(value.deepCopy(), observedAt, receivedAt, messageId));
        });
        if (next.equals(reported)) return this;
        var messages = new ArrayList<>(recentMessages);
        messages.add(messageId);
        if (messages.size() > 128) messages.remove(0);
        return new ShadowDocument(version + 1, modelVersion, next, desired,
                updatedAt == null || receivedAt.isAfter(updatedAt) ? receivedAt : updatedAt, messages);
    }
    public DeviceShadow view() {
        var delta = new TreeMap<String, JsonNode>();
        desired.forEach((id, target) -> {
            var actual = reported.get(id);
            if (actual == null || !equalValue(actual.value(), target.value())) delta.put(id, target.value());
        });
        return new DeviceShadow(version, modelVersion, reported, desired, Map.copyOf(delta), updatedAt);
    }
    private static boolean equalValue(JsonNode a, JsonNode b) {
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equalValue(a.get(i), b.get(i))) return false;
            return true;
        }
        return a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) == 0 : a.equals(b);
    }
}

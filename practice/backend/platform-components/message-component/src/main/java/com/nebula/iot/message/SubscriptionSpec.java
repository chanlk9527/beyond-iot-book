package com.nebula.iot.message;

import java.util.List;
import java.util.Objects;

/** Service subscriptions, never one Kafka group per App user or device. */
public record SubscriptionSpec(String name, List<String> patterns, Mode mode) {
    public enum Mode { SHARED, BROADCAST }

    public SubscriptionSpec {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Subscription name must be 1-100 letters, digits, _ or -");
        patterns = List.copyOf(patterns);
        if (patterns.isEmpty() || patterns.size() > 100) throw new IllegalArgumentException("Subscribe to 1-100 patterns");
        patterns.forEach(TopicPattern::validatePattern);
        Objects.requireNonNull(mode, "mode");
    }

    public static SubscriptionSpec shared(String name, String... patterns) {
        return new SubscriptionSpec(name, List.of(patterns), Mode.SHARED);
    }

    public static SubscriptionSpec broadcast(String name, String... patterns) {
        return new SubscriptionSpec(name, List.of(patterns), Mode.BROADCAST);
    }

    public boolean matches(String topic) {
        return patterns.stream().anyMatch(pattern -> TopicPattern.matches(pattern, topic));
    }
}

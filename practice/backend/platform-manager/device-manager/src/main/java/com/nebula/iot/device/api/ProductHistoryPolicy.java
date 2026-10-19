package com.nebula.iot.device.api;

import com.nebula.iot.storage.HistoryStore;
import java.util.*;

public record ProductHistoryPolicy(Rule defaults, Map<String,Rule> overrides, Rule eventDefaults, Map<String,Rule> eventOverrides) {
    public record Rule(boolean latest, String history, int days, int count, String write, int intervalSeconds, boolean aggregate, int aggregateDays) {
        public Rule {
            if (!Set.of("none","time","count").contains(history == null ? "" : history)
                    || !Set.of("every","change","interval").contains(write == null ? "" : write)
                    || days < 1 || days > 36500 || count < 1 || count > 1000000 || intervalSeconds < 1 || intervalSeconds > 86400
                    || aggregateDays < 1 || aggregateDays > 36500) throw new IllegalArgumentException("Invalid history rule");
        }
        public void validate(HistoryStore.Capabilities capabilities, boolean event) {
            if (event && (latest || aggregate || !write.equals("every"))) throw new IllegalArgumentException("Events require complete records, without latest values, sampling or property statistics");
            if (!history.equals("none") && !capabilities.enabled()) throw new IllegalArgumentException("History storage is not enabled");
            if (history.equals("time") && (!capabilities.timeRetention() || days > capabilities.maximumRetentionDays())) throw new IllegalArgumentException("Retention exceeds storage capability");
            if (history.equals("count") && !capabilities.countRetention()) throw new IllegalArgumentException("Strict count retention is not supported by this storage plan");
            if (!history.equals("none") && write.equals("change") && !capabilities.changeRecording()) throw new IllegalArgumentException("Change-only recording is not supported yet");
            if (!history.equals("none") && write.equals("interval") && !capabilities.intervalSampling()) throw new IllegalArgumentException("Interval sampling is not supported yet");
            if (aggregate && !capabilities.persistentAggregates()) throw new IllegalArgumentException("Persistent hourly statistics are not supported yet");
        }
    }
    public ProductHistoryPolicy {
        Objects.requireNonNull(defaults); Objects.requireNonNull(eventDefaults);
        overrides = Map.copyOf(overrides); eventOverrides = Map.copyOf(eventOverrides);
        if (overrides.size() > 500 || eventOverrides.size() > 500) throw new IllegalArgumentException("Too many history overrides");
        for (var id : java.util.stream.Stream.concat(overrides.keySet().stream(),eventOverrides.keySet().stream()).toList())
            if (!id.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid history identifier");
    }
    public static Rule initialRule(boolean event) { return new Rule(!event,"none",30,100,"every",60,false,365); }
    public static ProductHistoryPolicy initial() { return new ProductHistoryPolicy(initialRule(false),Map.of(),initialRule(true),Map.of()); }
    public Rule rule(HistoryStore.Kind kind, String id) { return kind == HistoryStore.Kind.PROPERTY ? overrides.getOrDefault(id,defaults) : eventOverrides.getOrDefault(id,eventDefaults); }
    public void validate(HistoryStore.Capabilities capabilities) {
        defaults.validate(capabilities,false); eventDefaults.validate(capabilities,true);
        overrides.values().forEach(r -> r.validate(capabilities,false)); eventOverrides.values().forEach(r -> r.validate(capabilities,true));
    }
}

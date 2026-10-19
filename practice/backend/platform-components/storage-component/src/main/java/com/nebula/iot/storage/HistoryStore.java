package com.nebula.iot.storage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Storage-independent observations. No device manager, transport or vendor SQL in this contract. */
public interface HistoryStore {
    enum Kind { PROPERTY, EVENT }
    record Capabilities(String provider, boolean enabled, boolean timeRetention, boolean countRetention,
                        boolean changeRecording, boolean intervalSampling, boolean persistentAggregates,
                        int maximumRetentionDays) {}
    record Series(String tenant, String device, String product, Kind kind, String identifier) {
        public Series {
            Objects.requireNonNull(kind);
            for (var value : List.of(tenant, device, product, identifier))
                if (value.isBlank() || !value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid series identity");
        }
    }
    record Observation(Series series, Instant occurredAt, String messageId, Instant receivedAt,
                       String modelVersion, String valueType, String payload) {
        public Observation {
            Objects.requireNonNull(series); Objects.requireNonNull(occurredAt); Objects.requireNonNull(receivedAt);
            if (messageId == null || messageId.isBlank() || messageId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128
                    || modelVersion == null || modelVersion.length() > 32 || valueType == null || valueType.length() > 16
                    || payload == null || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384)
                throw new IllegalArgumentException("History observation exceeds bounds");
            // First version uses millisecond precision, matching the device protocol.
            occurredAt = Instant.ofEpochMilli(occurredAt.toEpochMilli());
            receivedAt = Instant.ofEpochMilli(receivedAt.toEpochMilli());
        }
    }
    record Cursor(Instant occurredAt, String messageId) {
        public Cursor { Objects.requireNonNull(occurredAt); if (messageId == null || messageId.length() > 128) throw new IllegalArgumentException("Invalid cursor"); }
    }
    record Query(Series series, Instant from, Instant to, Cursor before, int limit) {
        public Query {
            Objects.requireNonNull(series); Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (!from.isBefore(to) || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid history query");
        }
    }
    record Page(List<Observation> items, Cursor next) { public Page { items = List.copyOf(items); } }
    Capabilities capabilities();
    /** A thrown failure may follow a partial write. Retrying identical observations must be idempotent. */
    void append(List<Observation> observations);
    Page query(Query query);
    /** Remove strictly older observations of this exact series; physical reclamation may be asynchronous. */
    void deleteBefore(Series series, Instant cutoff);
}

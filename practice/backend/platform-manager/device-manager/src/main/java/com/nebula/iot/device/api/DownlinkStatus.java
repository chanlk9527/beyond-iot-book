package com.nebula.iot.device.api;

import java.time.Instant;

/** Short-lived dispatch observation. MQTT delivery is not device execution. */
public record DownlinkStatus(String messageId, String commandId, String kind, String status,
        String code, String message, Instant createdAt, Instant expiresAt) {}

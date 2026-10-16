package com.nebula.iot.core.message;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.MessageId;
import com.nebula.iot.core.internal.Checks;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import com.nebula.iot.core.message.ReplyResult;

/** Enriched ingress envelope. Device clocks may differ from platform clocks. */
public record DeviceMessage(MessageId messageId, DeviceKey device, String modelVersion,
                            Instant occurredAt, Instant receivedAt,
                            MessageOrigin origin, DeviceMessageBody body, Optional<MessageId> replyTo, Optional<ReplyResult> result) {
    public DeviceMessage(MessageId messageId, DeviceKey device, String modelVersion, Instant occurredAt,
                         Instant receivedAt, MessageOrigin origin, DeviceMessageBody body) {
        this(messageId, device, modelVersion, occurredAt, receivedAt, origin, body, Optional.empty(), Optional.empty());
    }
    public DeviceMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(device, "device");
        modelVersion = Checks.nonBlank(modelVersion, "modelVersion");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(replyTo); Objects.requireNonNull(result);
        if (replyTo.isPresent() != result.isPresent()) throw new IllegalArgumentException("回复关联与结果必须同时存在");
        if (body instanceof DeviceMessageBody.ConnectionChanged && origin != MessageOrigin.GATEWAY) {
            throw new IllegalArgumentException("Connection changes require gateway origin");
        }
    }
}

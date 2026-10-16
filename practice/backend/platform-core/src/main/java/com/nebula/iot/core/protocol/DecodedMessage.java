package com.nebula.iot.core.protocol;

import com.nebula.iot.core.identity.MessageId;
import com.nebula.iot.core.message.DeviceMessageBody;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import com.nebula.iot.core.message.ReplyResult;

/** Codec output has no authority to assign a tenant, device or platform receive timestamp. */
public record DecodedMessage(MessageId messageId, Instant occurredAt, DeviceMessageBody body, Optional<MessageId> replyTo, Optional<ReplyResult> result) {
    public DecodedMessage(MessageId messageId, Instant occurredAt, DeviceMessageBody body) {
        this(messageId, occurredAt, body, Optional.empty(), Optional.empty());
    }
    public DecodedMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(replyTo); Objects.requireNonNull(result);
        if (replyTo.isPresent() != result.isPresent()) throw new IllegalArgumentException("回复关联与结果必须同时存在");
        if (body instanceof DeviceMessageBody.ConnectionChanged) {
            throw new IllegalArgumentException("Device codecs cannot emit gateway connection events");
        }
    }
}

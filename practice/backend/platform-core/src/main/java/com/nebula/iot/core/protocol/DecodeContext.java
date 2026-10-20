package com.nebula.iot.core.protocol;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.internal.Checks;
import com.nebula.iot.core.message.DeviceMessage;
import com.nebula.iot.core.message.MessageOrigin;
import java.time.Instant;
import java.util.Objects;

/** Constructed by ingress after authenticating the device and resolving its model version. */
public record DecodeContext(DeviceKey device, Transport transport, String modelVersion, Instant receivedAt,
                            com.nebula.iot.core.identity.ProductId productId) {
    public DecodeContext(DeviceKey device, Transport transport, String modelVersion, Instant receivedAt) {
        this(device, transport, modelVersion, receivedAt, null);
    }
    public DecodeContext {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(transport, "transport");
        modelVersion = Checks.nonBlank(modelVersion, "modelVersion");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public DeviceMessage bind(DecodedMessage decoded) {
        Objects.requireNonNull(decoded, "decoded");
        return new DeviceMessage(decoded.messageId(), device, modelVersion, decoded.occurredAt(),
                receivedAt, MessageOrigin.DEVICE, decoded.body(), decoded.replyTo(), decoded.result(), productId);
    }
}

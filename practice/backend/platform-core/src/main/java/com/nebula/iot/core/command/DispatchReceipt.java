package com.nebula.iot.core.command;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.CommandId;
import java.time.Instant;
import java.util.Objects;

/** Transport accepted work. This is neither device delivery nor execution confirmation. */
public record DispatchReceipt(CommandId commandId, DeviceKey device, Instant acceptedAt) {
    public DispatchReceipt {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}

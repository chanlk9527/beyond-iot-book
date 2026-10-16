package com.nebula.iot.core.command;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.CommandId;
import com.nebula.iot.core.internal.Checks;
import com.nebula.iot.core.model.Value;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** One execution intent. Retransmission keeps this ID; new user intent gets a new ID. */
public record DeviceCommand(CommandId commandId, DeviceKey device, String modelVersion,
                            String operationId, Map<String, Value> inputs,
                            Instant createdAt, Instant expiresAt) {
    public DeviceCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(device, "device");
        modelVersion = Checks.nonBlank(modelVersion, "modelVersion");
        operationId = Checks.nonBlank(operationId, "operationId");
        inputs = Map.copyOf(inputs);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Command expiry must be after creation");
        }
    }

    public boolean isExpiredAt(Instant instant) {
        return !Objects.requireNonNull(instant, "instant").isBefore(expiresAt);
    }
}

package com.nebula.iot.core.device;

import com.nebula.iot.core.command.DeviceCommand;
import com.nebula.iot.core.command.DispatchReceipt;
import java.util.concurrent.CompletionStage;

/** Operational handle, not an authorization token. Implementations enforce policy on each call. */
public interface DeviceOperator {
    DeviceKey key();

    CompletionStage<DeviceDescriptor> describe();

    /**
     * Reject mismatched device/tenant, expired commands, disallowed lifecycle and invalid model inputs.
     * Completion confirms transport acceptance only. Failures complete the stage exceptionally.
     * Persistence, retries and command feedback tracking belong to the command use case.
     */
    CompletionStage<DispatchReceipt> dispatch(DeviceCommand command);
}

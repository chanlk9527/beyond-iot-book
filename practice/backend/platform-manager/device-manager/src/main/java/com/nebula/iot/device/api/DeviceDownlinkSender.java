package com.nebula.iot.device.api;

import com.nebula.iot.core.command.DeviceDownlink;
import com.nebula.iot.core.command.DeliveryReceipt;
import java.util.concurrent.CompletionStage;

/** Transport port implemented by the bootstrap gateway adapter. */
@FunctionalInterface
public interface DeviceDownlinkSender {
    CompletionStage<DeliveryReceipt> send(DeviceDownlink message);
}

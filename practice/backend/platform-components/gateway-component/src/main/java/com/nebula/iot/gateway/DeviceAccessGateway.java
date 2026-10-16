package com.nebula.iot.gateway;

import com.nebula.iot.core.command.DeviceCommand;
import com.nebula.iot.core.command.DeviceDownlink;
import com.nebula.iot.core.command.DeliveryReceipt;
import com.nebula.iot.core.command.DispatchReceipt;
import com.nebula.iot.core.protocol.Transport;
import java.util.concurrent.CompletionStage;

/** 通用设备接入边界；业务通过设备身份投递，不依赖 Topic、套接字或具体传输库。 */
public interface DeviceAccessGateway {
    String id();
    Transport transport();
    CompletionStage<Void> start();
    CompletionStage<Void> stop();
    boolean isRunning();
    CompletionStage<DeliveryReceipt> send(DeviceDownlink message);
    default CompletionStage<DispatchReceipt> send(DeviceCommand command) {
        return send(DeviceDownlink.from(command)).thenApply(receipt ->
                new DispatchReceipt(command.commandId(), receipt.device(), receipt.acceptedAt()));
    }
}

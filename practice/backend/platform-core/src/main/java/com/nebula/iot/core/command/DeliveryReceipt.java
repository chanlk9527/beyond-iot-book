package com.nebula.iot.core.command;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.MessageId;
import java.time.Instant;
import java.util.Objects;

/** 通用下行的传输回执，不表示读取、配置或升级已经完成。 */
public record DeliveryReceipt(MessageId messageId, DeviceKey device, Instant acceptedAt) {
    public DeliveryReceipt {
        Objects.requireNonNull(messageId); Objects.requireNonNull(device); Objects.requireNonNull(acceptedAt);
    }
}

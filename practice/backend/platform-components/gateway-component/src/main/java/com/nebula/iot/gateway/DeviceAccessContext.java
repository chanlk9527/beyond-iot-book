package com.nebula.iot.gateway;

import com.nebula.iot.core.device.DeviceDescriptor;
import com.nebula.iot.core.model.DeviceModel;
import java.util.Objects;

/** 注册域提供的设备事实与精确模型快照；凭证验证和资源授权仍分别执行。 */
public record DeviceAccessContext(DeviceDescriptor device, DeviceModel model) {
    public DeviceAccessContext {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(model, "model");
        if (!device.product().equals(model.product()) || !device.modelVersion().equals(model.version())) {
            throw new IllegalArgumentException("设备与物模型不匹配");
        }
    }
}

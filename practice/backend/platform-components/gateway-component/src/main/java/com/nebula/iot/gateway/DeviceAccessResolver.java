package com.nebula.iot.gateway;

import com.nebula.iot.core.device.DeviceKey;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** 后续由注册管理模块实现；不存在的设备不能仅凭 ClientId临时注册。 */
@FunctionalInterface
public interface DeviceAccessResolver {
    CompletionStage<Optional<DeviceAccessContext>> resolve(DeviceKey device);
}

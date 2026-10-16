package com.nebula.iot.gateway;

import com.nebula.iot.core.message.DeviceMessage;
import java.util.concurrent.CompletionStage;

/**
 * 上行接收端口。成功完成表示接收方已经接下工作，MQTT才发送成功确认。
 * 是否持久化由实现负责；占位接收器不得声称具备重启恢复保证。
 */
@FunctionalInterface
public interface DeviceMessageHandler {
    CompletionStage<Void> handle(DeviceMessage message);
}

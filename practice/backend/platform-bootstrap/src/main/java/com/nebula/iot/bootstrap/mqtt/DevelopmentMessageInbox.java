package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.core.message.DeviceMessage;
import com.nebula.iot.gateway.DeviceMessageHandler;
import java.util.List;
import java.util.concurrent.*;

/** 开发用有界内存收件箱；成功接收不提供持久化、去重或重启恢复保证。 */
public final class DevelopmentMessageInbox implements DeviceMessageHandler {
    private final ArrayBlockingQueue<DeviceMessage> messages = new ArrayBlockingQueue<>(1024);
    @Override public CompletionStage<Void> handle(DeviceMessage message) {
        return messages.offer(message) ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new RejectedExecutionException("开发收件箱已满"));
    }
    public List<DeviceMessage> snapshot() { return List.copyOf(messages); }
}

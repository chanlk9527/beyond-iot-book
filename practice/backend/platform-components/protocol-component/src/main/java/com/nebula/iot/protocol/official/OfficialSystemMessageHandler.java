package com.nebula.iot.protocol.official;

import com.nebula.iot.core.command.*;
import com.nebula.iot.core.identity.MessageId;
import com.nebula.iot.core.message.*;
import com.nebula.iot.gateway.DeviceMessageHandler;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** 默认协议的系统消息处理；业务消息继续交给接收器，不伪造持久化及OTA任务处理。 */
public final class OfficialSystemMessageHandler implements DeviceMessageHandler {
    private final Function<DeviceDownlink, CompletionStage<DeliveryReceipt>> sender;
    private final DeviceMessageHandler delegate;
    private final Clock clock;
    public OfficialSystemMessageHandler(Function<DeviceDownlink, CompletionStage<DeliveryReceipt>> sender, DeviceMessageHandler delegate, Clock clock) {
        this.sender = Objects.requireNonNull(sender); this.delegate = Objects.requireNonNull(delegate); this.clock = Objects.requireNonNull(clock);
    }
    @Override public CompletionStage<Void> handle(DeviceMessage message) {
        if (!(message.body() instanceof DeviceMessageBody.TimeSyncRequest request)) return delegate.handle(message);
        var now = clock.instant();
        var response = new DeviceDownlink(new MessageId(UUID.randomUUID().toString()), message.device(), message.modelVersion(),
                now, now.plusSeconds(10), Optional.of(message.messageId()), Optional.of(ReplyResult.success()),
                new DownlinkBody.TimeSyncResponse(request.clientSendTime(), message.receivedAt().toEpochMilli()));
        return sender.apply(response).thenApply(receipt -> null);
    }
}
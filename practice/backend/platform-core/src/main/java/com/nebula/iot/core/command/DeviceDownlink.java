package com.nebula.iot.core.command;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.MessageId;
import com.nebula.iot.core.internal.Checks;
import com.nebula.iot.core.message.ReplyResult;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 通用下行信封；请求与回复都具有有效期，回复还必须关联原始消息。 */
public record DeviceDownlink(MessageId messageId, DeviceKey device, String modelVersion,
                             Instant createdAt, Instant expiresAt, Optional<MessageId> replyTo,
                             Optional<ReplyResult> result, DownlinkBody body) {
    public DeviceDownlink {
        Objects.requireNonNull(messageId); Objects.requireNonNull(device);
        modelVersion = Checks.nonBlank(modelVersion, "modelVersion");
        Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(replyTo); Objects.requireNonNull(result); Objects.requireNonNull(body);
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("下行有效期必须晚于创建时间");
        boolean response = body instanceof DownlinkBody.TimeSyncResponse || body instanceof DownlinkBody.ReportAcknowledgement;
        if (replyTo.isPresent() != response || result.isPresent() != response) throw new IllegalArgumentException("回复必须携带关联消息及应用结果，请求不能携带回复信息");
        if (replyTo.filter(messageId::equals).isPresent()) throw new IllegalArgumentException("回复不能复用请求消息编号");
        if (body instanceof DownlinkBody.TimeSyncResponse && !result.orElseThrow().isSuccess()) throw new IllegalArgumentException("时间响应必须成功");
    }
    public boolean isExpiredAt(Instant instant) { return !Objects.requireNonNull(instant).isBefore(expiresAt); }

    /** 兼容已有指令入口；一个命令对应一条逻辑下行，重传保持编号。 */
    public static DeviceDownlink from(DeviceCommand command) {
        return new DeviceDownlink(new MessageId(command.commandId().value()), command.device(), command.modelVersion(),
                command.createdAt(), command.expiresAt(), Optional.empty(), Optional.empty(),
                new DownlinkBody.OperationInvoke(command.commandId(), command.operationId(), command.inputs()));
    }
}

package com.nebula.iot.bootstrap.message;

import com.nebula.iot.core.message.DeviceMessage;
import com.nebula.iot.core.message.DeviceMessageBody;
import com.nebula.iot.message.EventMessage;
import java.util.Map;

final class DeviceMessageEventMapper {
    EventMessage map(DeviceMessage message) {
        var body = message.body();
        String suffix = switch (body) {
            case DeviceMessageBody.PropertyReport ignored -> "property/report";
            case DeviceMessageBody.PropertyReadReply ignored -> "property/read-reply";
            case DeviceMessageBody.PropertyWriteReply ignored -> "property/write-reply";
            case DeviceMessageBody.EventReport event -> "event/" + event.eventId();
            case DeviceMessageBody.CommandReply ignored -> "command/reply";
            case DeviceMessageBody.DeviceInfoReport ignored -> "info/report";
            case DeviceMessageBody.ConfigurationReport ignored -> "configuration/report";
            case DeviceMessageBody.ConfigurationReadReply ignored -> "configuration/read-reply";
            case DeviceMessageBody.ConfigurationSetReply ignored -> "configuration/set-reply";
            case DeviceMessageBody.TimeSyncRequest ignored -> "system/time-request";
            case DeviceMessageBody.OtaVersionReport ignored -> "ota/version-report";
            case DeviceMessageBody.OtaUpgradeReply ignored -> "ota/upgrade-reply";
            case DeviceMessageBody.OtaCancelReply ignored -> "ota/cancel-reply";
            case DeviceMessageBody.OtaProgressReport ignored -> "ota/progress-report";
            case DeviceMessageBody.RequestFailure ignored -> "request/failure";
            case DeviceMessageBody.ConnectionChanged ignored -> "connection/changed";
        };
        var device = message.device();
        return new EventMessage(message.messageId().value(), "device/" + device.deviceId().value() + "/" + suffix,
                message.occurredAt(), message.receivedAt(), device.tenantId().value(), device.deviceId().value(),
                message.modelVersion(), message.messageId().value(), message.origin().name(), Map.of(), body);
    }
}
package com.nebula.iot.core.message;

import com.nebula.iot.core.command.ExecutionStatus;
import com.nebula.iot.core.device.ConnectionState;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.internal.Checks;
import com.nebula.iot.core.model.Value;
import java.util.*;

/** 共享的上行业务语义；供应商协议转换为这些类型后进入平台。 */
public sealed interface DeviceMessageBody {
    record PropertyReport(Map<String, Value> properties) implements DeviceMessageBody {
        public PropertyReport { properties = Map.copyOf(properties); }
    }
    record EventReport(String eventId, Map<String, Value> fields) implements DeviceMessageBody {
        public EventReport { eventId = Checks.nonBlank(eventId, "eventId"); fields = Map.copyOf(fields); }
    }
    record CommandReply(CommandId commandId, ExecutionStatus status, Optional<String> errorCode) implements DeviceMessageBody {
        public CommandReply {
            Objects.requireNonNull(commandId); Objects.requireNonNull(status); Objects.requireNonNull(errorCode);
            errorCode.ifPresent(code -> Checks.nonBlank(code, "errorCode"));
            if ((status == ExecutionStatus.FAILED) != errorCode.isPresent()) throw new IllegalArgumentException("仅失败反馈必须包含错误码");
        }
    }
    record PropertyReadReply(Map<String, Value> properties) implements DeviceMessageBody {
        public PropertyReadReply { properties = Map.copyOf(properties); }
    }
    record PropertyWriteReply(Map<String, Value> properties) implements DeviceMessageBody {
        public PropertyWriteReply { properties = Map.copyOf(properties); }
    }
    record DeviceInfoReport(String firmwareVersion, String hardwareVersion, String bootId, Optional<String> sdkVersion, boolean clockSynced) implements DeviceMessageBody {
        public DeviceInfoReport {
            firmwareVersion = Checks.nonBlank(firmwareVersion, "firmwareVersion"); hardwareVersion = Checks.nonBlank(hardwareVersion, "hardwareVersion");
            bootId = Checks.nonBlank(bootId, "bootId"); Objects.requireNonNull(sdkVersion); sdkVersion.ifPresent(value -> Checks.nonBlank(value, "sdkVersion"));
        }
    }
    record ConfigurationReport(String scope, String version, Map<String, Value> values) implements DeviceMessageBody {
        public ConfigurationReport { scope = Checks.nonBlank(scope, "scope"); version = Checks.nonBlank(version, "version"); values = Map.copyOf(values); }
    }
    record ConfigurationReadReply(String scope, String version, Map<String, Value> values) implements DeviceMessageBody {
        public ConfigurationReadReply { scope = Checks.nonBlank(scope, "scope"); version = Checks.nonBlank(version, "version"); values = Map.copyOf(values); }
    }
    record ConfigurationSetReply(String scope, String version) implements DeviceMessageBody {
        public ConfigurationSetReply { scope = Checks.nonBlank(scope, "scope"); version = Checks.nonBlank(version, "version"); }
    }
    record TimeSyncRequest(long clientSendTime) implements DeviceMessageBody {
        public TimeSyncRequest { if (clientSendTime < 0) throw new IllegalArgumentException("客户端时间不能为负数"); }
    }
    record OtaVersionReport(Map<String, String> versions) implements DeviceMessageBody {
        public OtaVersionReport {
            versions = Map.copyOf(versions);
            if (versions.isEmpty() || versions.size() > 16) throw new IllegalArgumentException("组件版本数量无效");
            versions.forEach((component, version) -> { Checks.nonBlank(component, "component"); Checks.nonBlank(version, "version"); });
        }
    }
    record OtaUpgradeReply(String taskId, String attemptId) implements DeviceMessageBody {
        public OtaUpgradeReply { taskId = Checks.nonBlank(taskId, "taskId"); attemptId = Checks.nonBlank(attemptId, "attemptId"); }
    }
    record OtaCancelReply(String taskId, String attemptId) implements DeviceMessageBody {
        public OtaCancelReply { taskId = Checks.nonBlank(taskId, "taskId"); attemptId = Checks.nonBlank(attemptId, "attemptId"); }
    }
    enum OtaStage { RECEIVED, DOWNLOADING, VERIFYING, INSTALLING, REBOOTING, SUCCEEDED, FAILED, CANCELED }
    record OtaProgressReport(String taskId, String attemptId, MessageId upgradeMessageId, String component,
                             String targetVersion, OtaStage stage, int progress, Optional<String> installedVersion,
                             Optional<String> errorCode) implements DeviceMessageBody {
        public OtaProgressReport {
            taskId = Checks.nonBlank(taskId, "taskId"); attemptId = Checks.nonBlank(attemptId, "attemptId");
            Objects.requireNonNull(upgradeMessageId); component = Checks.nonBlank(component, "component"); targetVersion = Checks.nonBlank(targetVersion, "targetVersion");
            Objects.requireNonNull(stage); Objects.requireNonNull(installedVersion); Objects.requireNonNull(errorCode);
            installedVersion.ifPresent(version -> Checks.nonBlank(version, "installedVersion")); errorCode.ifPresent(code -> Checks.nonBlank(code, "errorCode"));
            if (progress < 0 || progress > 100) throw new IllegalArgumentException("升级进度必须在0至100之间");
            if (stage == OtaStage.SUCCEEDED && (progress != 100 || !installedVersion.orElse("").equals(targetVersion))) throw new IllegalArgumentException("升级成功需要完整进度及目标版本确认");
            if ((stage == OtaStage.FAILED) != errorCode.isPresent()) throw new IllegalArgumentException("仅升级失败必须包含错误码");
        }
    }
    enum RequestKind { PROPERTY_READ, PROPERTY_WRITE, CONFIGURATION_READ, CONFIGURATION_SET, OTA_UPGRADE, OTA_CANCEL }
    record RequestFailure(RequestKind kind) implements DeviceMessageBody {
        public RequestFailure { Objects.requireNonNull(kind); }
    }
    /** 连接变化只由可信会话组件产生，设备载荷不能伪造。 */
    record ConnectionChanged(ConnectionState state) implements DeviceMessageBody {
        public ConnectionChanged { Objects.requireNonNull(state); }
    }
}

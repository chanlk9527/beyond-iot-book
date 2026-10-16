package com.nebula.iot.core.command;

import com.nebula.iot.core.identity.CommandId;
import com.nebula.iot.core.internal.Checks;
import com.nebula.iot.core.model.Value;
import java.net.URI;
import java.util.*;

/** 与传输无关的下行业务；协议实现负责将其映射为具体报文。 */
public sealed interface DownlinkBody {
    record PropertyRead(List<String> properties) implements DownlinkBody {
        public PropertyRead {
            properties = List.copyOf(properties);
            if (properties.isEmpty() || properties.size() > 256 || new HashSet<>(properties).size() != properties.size()) throw new IllegalArgumentException("读取属性清单无效");
            properties.forEach(id -> Checks.nonBlank(id, "property"));
        }
    }
    record PropertyWrite(Map<String, Value> properties) implements DownlinkBody {
        public PropertyWrite { properties = Map.copyOf(properties); if (properties.isEmpty()) throw new IllegalArgumentException("写入属性不能为空"); }
    }
    record OperationInvoke(CommandId commandId, String operationId, Map<String, Value> inputs) implements DownlinkBody {
        public OperationInvoke { Objects.requireNonNull(commandId); operationId = Checks.nonBlank(operationId, "operationId"); inputs = Map.copyOf(inputs); }
    }
    record ConfigurationRead(String scope) implements DownlinkBody {
        public ConfigurationRead { scope = Checks.nonBlank(scope, "scope"); }
    }
    record ConfigurationSet(String scope, String version, Optional<String> expectedVersion, Map<String, Value> values) implements DownlinkBody {
        public ConfigurationSet {
            scope = Checks.nonBlank(scope, "scope"); version = Checks.nonBlank(version, "version");
            Objects.requireNonNull(expectedVersion); expectedVersion.ifPresent(value -> Checks.nonBlank(value, "expectedVersion"));
            values = Map.copyOf(values);
        }
    }
    /** 服务端发送时间在序列化时生成，减少排队时间对校时的影响。 */
    record TimeSyncResponse(long clientSendTime, long serverReceiveTime) implements DownlinkBody {
        public TimeSyncResponse { if (clientSendTime < 0 || serverReceiveTime < 0) throw new IllegalArgumentException("时间不能为负数"); }
    }
    enum ReportKind { PROPERTY, EVENT, INFO, CONFIGURATION, OTA_VERSION, OTA_PROGRESS }
    record ReportAcknowledgement(ReportKind kind) implements DownlinkBody {
        public ReportAcknowledgement { Objects.requireNonNull(kind); }
    }
    record FirmwarePackage(URI url, long size, String sha256, String keyId, String signature) {
        public FirmwarePackage {
            Objects.requireNonNull(url);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null) throw new IllegalArgumentException("固件地址必须为不带用户信息和片段的HTTPS地址");
            if (size < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("固件大小或摘要无效");
            keyId = Checks.nonBlank(keyId, "keyId");
            if (signature == null || Base64.getDecoder().decode(signature).length != 64) throw new IllegalArgumentException("Ed25519签名必须为64字节的Base64编码");
        }
        @Override public String toString() { return "FirmwarePackage[size=" + size + ", 已隐藏下载地址和签名]"; }
    }
    record OtaUpgrade(String taskId, String attemptId, String component, String fromVersion,
                      String targetVersion, String hardwareVersion, FirmwarePackage firmware) implements DownlinkBody {
        public OtaUpgrade {
            taskId = Checks.nonBlank(taskId, "taskId"); attemptId = Checks.nonBlank(attemptId, "attemptId");
            component = Checks.nonBlank(component, "component"); fromVersion = Checks.nonBlank(fromVersion, "fromVersion");
            targetVersion = Checks.nonBlank(targetVersion, "targetVersion"); hardwareVersion = Checks.nonBlank(hardwareVersion, "hardwareVersion");
            Objects.requireNonNull(firmware);
            if (fromVersion.equals(targetVersion)) throw new IllegalArgumentException("首版不允许同版本升级");
        }
    }
    record OtaCancel(String taskId, String attemptId) implements DownlinkBody {
        public OtaCancel { taskId = Checks.nonBlank(taskId, "taskId"); attemptId = Checks.nonBlank(attemptId, "attemptId"); }
    }
}

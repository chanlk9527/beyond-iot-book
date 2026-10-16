package com.nebula.iot.network.mqtt;

import com.nebula.iot.core.auth.*;
import com.nebula.iot.core.device.*;
import com.nebula.iot.gateway.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** 显式开发模式使用的内存目录；后续由设备注册域和正式凭证服务替换。 */
public final class DevelopmentDeviceDirectory implements DeviceAuthenticator, DeviceAccessResolver {
    private final Map<String, Registration> users;
    private final Map<DeviceKey, DeviceAccessContext> devices;

    public DevelopmentDeviceDirectory(List<Registration> registrations) {
        var users = new HashMap<String, Registration>();
        var devices = new HashMap<DeviceKey, DeviceAccessContext>();
        var clients = new HashSet<String>();
        if (registrations.isEmpty()) throw new IllegalArgumentException("开发模式至少配置一台设备");
        for (var entry : registrations) {
            if (users.putIfAbsent(entry.username, entry) != null || devices.putIfAbsent(entry.access.device().key(), entry.access) != null
                    || !clients.add(entry.clientId)) throw new IllegalArgumentException("开发设备的身份、用户名和ClientId必须唯一");
        }
        this.users = Map.copyOf(users);
        this.devices = Map.copyOf(devices);
    }

    @Override
    public CompletionStage<AuthenticationResult> authenticate(DeviceAuthenticationRequest request) {
        if (request instanceof MqttAuthenticationRequest mqtt) {
            var entry = users.get(mqtt.principal());
            var supplied = mqtt.password();
            try {
                if (entry != null && entry.clientId.equals(mqtt.clientId()) && MessageDigest.isEqual(entry.password, supplied)) {
                    return CompletableFuture.completedFuture(new AuthenticationResult.Accepted(entry.access.device().key()));
                }
            } finally { Arrays.fill(supplied, (byte) 0); }
        }
        return CompletableFuture.completedFuture(new AuthenticationResult.Rejected("INVALID_CREDENTIALS"));
    }

    @Override
    public CompletionStage<Optional<DeviceAccessContext>> resolve(DeviceKey key) {
        return CompletableFuture.completedFuture(Optional.ofNullable(devices.get(key)));
    }

    /** 占位目录保留开发密码副本，禁止作为生产凭证存储或输出到日志。 */
    public static final class Registration {
        private final String clientId;
        private final String username;
        private final byte[] password;
        private final DeviceAccessContext access;
        public Registration(String clientId, String username, byte[] password, DeviceAccessContext access) {
            if (clientId == null || clientId.isBlank() || username == null || username.isBlank() || password == null || password.length == 0) {
                throw new IllegalArgumentException("开发认证配置不能为空");
            }
            this.clientId = clientId; this.username = username; this.password = password.clone(); this.access = Objects.requireNonNull(access);
        }
        @Override public String toString() { return "Registration[已隐藏认证材料]"; }
    }
}

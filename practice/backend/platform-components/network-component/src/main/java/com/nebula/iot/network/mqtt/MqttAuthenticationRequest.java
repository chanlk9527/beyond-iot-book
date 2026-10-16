package com.nebula.iot.network.mqtt;

import com.nebula.iot.core.auth.DeviceAuthenticationRequest;
import com.nebula.iot.core.identity.TenantId;
import com.nebula.iot.core.protocol.Transport;
import java.util.Arrays;
import java.util.Optional;

/** 连接认证材料只在认证阶段保留；日志不输出用户名或密码。 */
public final class MqttAuthenticationRequest implements DeviceAuthenticationRequest, AutoCloseable {
    private final String clientId;
    private final String username;
    private final byte[] password;

    public MqttAuthenticationRequest(String clientId, String username, byte[] password) {
        this.clientId = clientId;
        this.username = username;
        this.password = password == null ? new byte[0] : password.clone();
    }

    public String clientId() { return clientId; }
    @Override public String principal() { return username; }
    public byte[] password() { return password.clone(); }
    @Override public Transport transport() { return Transport.MQTT; }
    @Override public Optional<TenantId> tenantHint() { return Optional.empty(); }
    @Override public void close() { Arrays.fill(password, (byte) 0); }
    @Override public String toString() { return "MqttAuthenticationRequest[已隐藏认证材料]"; }
}

package com.nebula.iot.protocol.official;

import com.nebula.iot.core.auth.DeviceAuthenticator;
import com.nebula.iot.core.protocol.*;
import java.util.*;

/** 默认协议提供者；后续供应商协议实现同一SPI，动态加载与选择器暂不实现。 */
public final class OfficialMqttProtocol implements ProtocolSupport {
    private final DeviceAuthenticator authenticator;
    private final DeviceMessageCodec codec;
    public OfficialMqttProtocol(DeviceAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator);
        this.codec = new OfficialMqttCodec();
    }
    @Override public String id() { return "nebula-mqtt-v1"; }
    @Override public Set<Transport> supportedTransports() { return Set.of(Transport.MQTT); }
    @Override public DeviceAuthenticator authenticator() { return authenticator; }
    @Override public DeviceMessageCodec codec(Transport transport) {
        if (!supportedTransports().contains(transport)) throw new IllegalArgumentException("不支持的传输");
        return codec;
    }
}
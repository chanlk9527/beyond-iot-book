package com.nebula.iot.bootstrap.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.List;

/** 接入配置与开发设备清单；开发密码通过环境变量注入，禁止打印完整配置。 */
@ConfigurationProperties("nebula.mqtt")
public record MqttAccessProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("127.0.0.1") String host, @DefaultValue("1883") int port,
        @DefaultValue("1000") int maxConnections, @DefaultValue("65536") int maxMessageBytes,
        @DefaultValue("32") int maxInFlight, @DefaultValue("4") int workerThreads,
        @DefaultValue("256") int maxQueuedWork, @DefaultValue("5s") Duration authenticationTimeout,
        @DefaultValue("10s") Duration processingTimeout, @DefaultValue("5s") Duration acknowledgementTimeout,
        @DefaultValue("false") boolean developmentMode, List<DevelopmentDevice> devices,
        @DefaultValue("true") boolean websocketEnabled, @DefaultValue("-1") int websocketPort) {
    public MqttAccessProperties { devices = devices == null ? List.of() : List.copyOf(devices); }

    public record DevelopmentDevice(String tenantId, String deviceId, String productId,
                                    String clientId, String username, String password) {
        @Override public String toString() { return "DevelopmentDevice[已隐藏认证材料]"; }
    }
}

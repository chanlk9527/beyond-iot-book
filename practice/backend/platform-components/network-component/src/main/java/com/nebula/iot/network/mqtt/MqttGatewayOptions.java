package com.nebula.iot.network.mqtt;

import java.time.Duration;
import java.util.Objects;

/** MQTT服务端资源边界；端口0用于测试时申请空闲端口。 */
public record MqttGatewayOptions(String host, int port, int maxConnections, int maxMessageBytes,
                                 int maxInFlight, int workerThreads, int maxQueuedWork,
                                 Duration authenticationTimeout, Duration processingTimeout,
                                 Duration acknowledgementTimeout) {
    public MqttGatewayOptions {
        if (host == null || host.isBlank() || port < 0 || port > 65535 || maxConnections < 1
                || maxMessageBytes < 1024 || maxInFlight < 1 || maxInFlight > 65535
                || workerThreads < 1 || maxQueuedWork < 1) {
            throw new IllegalArgumentException("MQTT配置超出范围");
        }
        for (var timeout : new Duration[] {authenticationTimeout, processingTimeout, acknowledgementTimeout}) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.toMillis() < 1 || timeout.toMillis() > 60000) {
                throw new IllegalArgumentException("MQTT超时必须在1毫秒至60秒之间");
            }
        }
    }

    public static MqttGatewayOptions local(int port) {
        return new MqttGatewayOptions("127.0.0.1", port, 1000, 65536, 32, 4, 256,
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5));
    }
}

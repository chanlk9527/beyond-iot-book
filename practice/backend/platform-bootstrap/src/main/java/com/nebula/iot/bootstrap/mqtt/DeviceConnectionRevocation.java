package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.device.api.DeviceConnectionDirectory;
import com.nebula.iot.network.mqtt.VertxMqttGateway;
import org.springframework.transaction.event.TransactionalEventListener;

public final class DeviceConnectionRevocation {
    private final VertxMqttGateway gateway;
    public DeviceConnectionRevocation(VertxMqttGateway gateway) { this.gateway = gateway; }
    @TransactionalEventListener
    public void revoked(DeviceConnectionDirectory.Revoked event) {
        gateway.disconnectDevice(event.device()).toCompletableFuture().join();
    }
}

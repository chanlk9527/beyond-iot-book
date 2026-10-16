package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.network.mqtt.VertxMqttGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.test.annotation.DirtiesContext;
import static org.junit.jupiter.api.Assertions.*;

/** 开发占位必须显式开启，并能随Spring生命周期启动真实监听端口。 */
@SpringBootTest(properties = {
        "nebula.mqtt.enabled=true", "nebula.mqtt.development-mode=true", "nebula.mqtt.port=0",
        "nebula.mqtt.devices[0].tenant-id=test-tenant", "nebula.mqtt.devices[0].device-id=warmer",
        "nebula.mqtt.devices[0].product-id=warmer", "nebula.mqtt.devices[0].client-id=test-warmer",
        "nebula.mqtt.devices[0].username=test-user", "nebula.mqtt.devices[0].password=test-password"
})
@DirtiesContext
class MqttBootstrapTest {
    @Autowired VertxMqttGateway gateway;
    @Autowired DevelopmentMessageInbox inbox;
    @Autowired HealthEndpoint health;

    @Test
    void startsConfiguredGatewayAndReportsItsHealth() {
        assertTrue(gateway.isRunning());
        assertTrue(gateway.actualPort() > 0);
        assertEquals("UP", health.health().getStatus().getCode());
        assertTrue(inbox.snapshot().isEmpty());
    }
}

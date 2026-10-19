package com.nebula.iot.bootstrap.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import com.nebula.iot.network.mqtt.VertxMqttGateway;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:access-nodes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "DATABASE_PASSWORD=test",
        "nebula.management.initial-account.username=node-admin",
        "nebula.management.initial-account.password=test-password",
        "nebula.management.initial-account.platform-admin=true",
        "nebula.mqtt.enabled=true", "nebula.mqtt.development-mode=true", "nebula.mqtt.port=0",
        "nebula.mqtt.devices[0].tenant-id=test-tenant", "nebula.mqtt.devices[0].device-id=warmer",
        "nebula.mqtt.devices[0].product-id=warmer", "nebula.mqtt.devices[0].client-id=test-warmer",
        "nebula.mqtt.devices[0].username=test-user", "nebula.mqtt.devices[0].password=test-password"
})
@ActiveProfiles("management")
@AutoConfigureMockMvc
@DirtiesContext
class AccessNodeApiTest {
    private static final String URL = "/api/management/access/nodes";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VertxMqttGateway gateway;

    @Test void requiresAuthentication() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test @WithMockUser("node-tenant")
    void tenantAdministratorCannotReadPlatformMetrics() throws Exception {
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('node-tenant','Tenant')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,tenant_id) VALUES ('node-user','node-tenant','unused','node-tenant')");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('node-tenant','node-user','OWNER')");
        mvc.perform(get(URL)).andExpect(status().isForbidden());
    }

    @Test @WithMockUser("node-admin")
    void observesRealConnectionUplinkAndDisconnect() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.scope").value("LOCAL_PROCESS"))
                .andExpect(jsonPath("$.nodes[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.nodes[0].metrics.onlineDevices").value(0))
                .andExpect(jsonPath("$.nodes[0].metrics.port").value(gateway.actualPort()));
        var client = new MqttClient("tcp://127.0.0.1:" + gateway.actualPort(), "test-warmer", new MemoryPersistence());
        try {
            var options = new MqttConnectionOptions();
            options.setUserName("test-user");
            options.setPassword("test-password".getBytes(StandardCharsets.UTF_8));
            options.setCleanStart(true);
            options.setSessionExpiryInterval(0L);
            client.setTimeToWait(5000);
            client.connect(options);
            client.publish("nebula/v1/up", """
                    {"version":"1.0","method":"thing.property.report","messageId":"node-report-1","timestamp":0,"data":{"properties":{"heatingLevel":70}}}
                    """.getBytes(StandardCharsets.UTF_8), 1, false);
            mvc.perform(get(URL)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.nodes[0].metrics.onlineDevices").value(1))
                    .andExpect(jsonPath("$.nodes[0].metrics.connections").value(1))
                    .andExpect(jsonPath("$.nodes[0].metrics.acceptedMessages").value(1));
            client.disconnect();
        } finally {
            if (client.isConnected()) client.disconnectForcibly(0, 0, false);
            client.close(true);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gateway.sessionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].metrics.onlineDevices").value(0));
    }
}

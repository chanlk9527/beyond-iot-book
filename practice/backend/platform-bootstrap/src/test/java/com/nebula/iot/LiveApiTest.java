package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.media.api.MediaProvider;
import com.nebula.iot.media.api.MediaProvider.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:live;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test","nebula.mqtt.enabled=false",
    "nebula.app.video.enabled=true","nebula.app.video.app-id=home-app","nebula.app.video.signing-secret=test-secret",
    "nebula.video.channels[0].scope=ta","nebula.video.channels[0].device-id=d1",
    "nebula.video.channels[0].channel-id=main","nebula.video.channels[0].resource=private-channel"})
@ActiveProfiles("management") @AutoConfigureMockMvc @Import(LiveApiTest.Media.class)
class LiveApiTest {
    @TestConfiguration static class Media {
        @Bean @Primary MediaProvider testProvider() {
            return (resource, client) -> new Connection() {
                boolean open = true;
                public List<IceServer> iceServers() { return List.of(new IceServer(List.of("turn:example.test"),"viewer","turn-password")); }
                public Instant expiresAt() { return Instant.now().plusSeconds(300); }
                public void send(Signal signal) {}
                public List<Signal> drain() { return List.of(); }
                public boolean isOpen() { return open; }
                public void close() { open = false; }
            };
        }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.nebula.iot.video.api.LiveAccessPolicy policy;
    @Autowired ObjectMapper json;
    static final String PATH = "/api/management/video/sessions";
    static final String APP_PATH = "/api/app/video/sessions";
    static final String INPUT = "{\"tenantId\":\"ta\",\"deviceId\":\"d1\",\"channelId\":\"main\"}";
    @BeforeEach void seed() {
        for (String table : List.of("device_management_audit","device_registration","device_model_version","device_product",
                "identity_account_role","identity_role_permission","identity_role","tenant_membership","identity_account","tenant")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','A'),('tb','B')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'tb'),('n','no-video','unused',FALSE,'ta')");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','a','VIEWER'),('tb','b','OWNER'),('ta','n','OWNER')");
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('r','live','Live'),('n','read-only','Read only')");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('r','device:read'),('r','video:watch'),('n','device:read')");
        jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES ('a','r'),('b','r'),('n','n')");
        String model="{\"properties\":{},\"events\":{},\"operations\":{}}";
        jdbc.update("INSERT INTO device_product(id,tenant_id,scope_key,code,name,draft_model,latest_version) VALUES ('p','ta','ta','camera','Camera',?,'v1')",model);
        jdbc.update("INSERT INTO device_model_version(product_id,version,model_json) VALUES ('p','v1',?)",model);
        jdbc.update("INSERT INTO device_registration(id,tenant_id,scope_key,product_id,code,name,model_version,lifecycle,credential_hash) VALUES ('d1','ta','ta','p','camera','Camera','v1','ACTIVE','unused')");
    }
    String create() throws Exception {
        String body = mvc.perform(post(PATH).with(user("alice")).with(csrf()).contentType("application/json").content(INPUT))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.protocol").value("webrtc")).andExpect(jsonPath("$.iceServers[0].credential").value("turn-password"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-channel")); assertFalse(body.contains("Signature"));
        return json.readTree(body).path("sessionId").asText();
    }
    @Test void appServerClaimCanCreateSessionWithoutManagementSession() throws Exception {
        String timestamp = Long.toString(Instant.now().toEpochMilli());
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post(APP_PATH)
                .header("X-Nebula-App-Id", "home-app")
                .header("X-Nebula-App-Request-Id", requestId)
                .header("X-Nebula-App-Subject", "app-user-1")
                .header("X-Nebula-App-Tenant", "ta")
                .header("X-Nebula-App-Device", "d1")
                .header("X-Nebula-App-Channel", "main")
                .header("X-Nebula-App-Timestamp", timestamp)
                .header("X-Nebula-App-Signature", signApp("app-user-1", "ta", "d1", "main", timestamp, requestId)))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"));
    }
    private static String signApp(String subject, String tenant, String device, String channel, String timestamp, String requestId) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(
                ("home-app\n"+subject+"\n"+tenant+"\n"+device+"\n"+channel+"\n"+timestamp+"\n"+requestId).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void requiresLoginCsrfPermissionAndActualTenantMembership() throws Exception {
        mvc.perform(post(PATH).with(csrf()).contentType("application/json").content(INPUT)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(user("alice")).contentType("application/json").content(INPUT)).andExpect(status().isForbidden());
        for (String actor : List.of("bob","no-video"))
            mvc.perform(post(PATH).with(user(actor)).with(csrf()).contentType("application/json").content(INPUT)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(user("alice")).with(csrf()).contentType("application/json").content(INPUT.replace("\"ta\"","null"))).andExpect(status().isForbidden());
        create();
    }
    @Test void ownershipRevocationAndClosedSession() throws Exception {
        String id=create();
        mvc.perform(get(PATH+"/"+id+"/signals").with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(delete(PATH+"/"+id).with(user("bob")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(PATH+"/"+id+"/signals").with(user("alice")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        jdbc.update("DELETE FROM identity_role_permission WHERE permission='video:watch'");
        mvc.perform(get(PATH+"/"+id+"/signals").with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH+"/"+id+"/signals").with(user("alice"))).andExpect(status().isNotFound());
        mvc.perform(delete(PATH+"/"+id).with(user("alice")).with(csrf())).andExpect(status().isNoContent());
    }
    @Test void appIdentityUsesAuthoritativeDeviceState() {
        var target = policy.requireWatch(new com.nebula.iot.video.api.LiveAccessPolicy.Identity(
                com.nebula.iot.video.api.LiveAccessPolicy.Audience.APP,"alice"),"ta","d1","main");
        assertEquals(new com.nebula.iot.video.api.LiveAccessPolicy.Target("ta","d1","main"), target);
    }
    @Test void rejectsInactiveDeviceAndProduct() throws Exception {
        jdbc.update("UPDATE device_registration SET lifecycle='DISABLED'");
        mvc.perform(post(PATH).with(user("alice")).with(csrf()).contentType("application/json").content(INPUT)).andExpect(status().isConflict());
        jdbc.update("UPDATE device_registration SET lifecycle='ACTIVE'");
        jdbc.update("UPDATE device_product SET enabled=FALSE");
        mvc.perform(post(PATH).with(user("alice")).with(csrf()).contentType("application/json").content(INPUT)).andExpect(status().isConflict());
    }
}

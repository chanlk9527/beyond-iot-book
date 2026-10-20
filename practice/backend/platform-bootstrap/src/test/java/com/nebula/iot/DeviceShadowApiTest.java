package com.nebula.iot;

import com.fasterxml.jackson.databind.*;
import com.nebula.iot.bootstrap.message.EventPublishingDeviceMessageHandler;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.device.domain.*;
import com.nebula.iot.message.EventBus;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:shadow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test","nebula.redis.enabled=false"})
@ActiveProfiles("management")
@AutoConfigureMockMvc
@Import(DeviceShadowApiTest.Stores.class)
class DeviceShadowApiTest {
    @TestConfiguration static class Stores {
        @Bean MemoryStore testShadowStore() { return new MemoryStore(); }
    }
    static class MemoryStore implements DeviceShadowStore {
        final Map<DeviceKey, ShadowDocument> documents = new HashMap<>();
        boolean unavailable;
        @Override public synchronized ShadowDocument read(DeviceKey key, String model) {
            if (unavailable) throw new org.springframework.data.redis.RedisConnectionFailureException("test failure");
            return documents.getOrDefault(key, ShadowDocument.empty(model));
        }
        @Override public synchronized boolean compareAndSet(DeviceKey key, long version, ShadowDocument doc) {
            if (unavailable) throw new org.springframework.data.redis.RedisConnectionFailureException("test failure");
            if (documents.getOrDefault(key, ShadowDocument.empty(doc.modelVersion())).version() != version) return false;
            documents.put(key, doc); return true;
        }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MemoryStore store;
    @Autowired EventBus bus;
    static final String A = "/api/management/catalog/tenants/ta/devices/d1/shadow";
    static final String B = "/api/management/catalog/tenants/tb/devices/d2/shadow";
    static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
    @BeforeEach void seed() throws Exception {
        store.documents.clear(); store.unavailable = false;
        for (String table : List.of("device_management_audit","device_registration","device_model_version","device_product",
                "identity_account_role","identity_role_permission","identity_role","tenant_membership","identity_account","tenant"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','A'),('tb','B')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('root','root','unused',TRUE,NULL),('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'tb'),('v','viewer','unused',FALSE,'ta')");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','a','OWNER'),('tb','b','ADMIN'),('ta','v','VIEWER')");
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('r','shadow','Shadow')");
        for (String permission : List.of("device:read","device:write"))
            jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('r',?)", permission);
        for (String id : List.of("a","b","v")) jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES (?,'r')", id);
        String model = """
            {"properties":{"samples":{"id":"samples","access":"READ_WRITE","schema":{"type":"ARRAY","items":{"type":"DECIMAL","minimum":0,"maximum":100},"minItems":1,"maxItems":3}},
              "power":{"id":"power","schema":{"type":"BOOLEAN"},"access":"READ_WRITE"},
              "level":{"id":"level","schema":{"type":"INTEGER","minimum":1,"maximum":100},"access":"READ_WRITE"},
              "temperature":{"id":"temperature","schema":{"type":"DECIMAL"},"access":"READ_ONLY"},
              "secret":{"id":"secret","schema":{"type":"TEXT"},"access":"WRITE_ONLY"}},
             "events":{},"operations":{}}
            """;
        for (String tenant : List.of("ta","tb")) {
            String product = "p-" + tenant, device = tenant.equals("ta") ? "d1" : "d2";
            jdbc.update("INSERT INTO device_product(id,tenant_id,scope_key,code,name,draft_model,latest_version) VALUES (?,?,?,?,?,?,'v1')",
                    product,tenant,tenant,"warmer","Warmer",model);
            jdbc.update("INSERT INTO device_model_version(product_id,version,model_json) VALUES (?,'v1',?)",product,model);
            jdbc.update("INSERT INTO device_registration(id,tenant_id,scope_key,product_id,code,name,model_version,lifecycle,credential_hash) VALUES (?,?,?,?,?,?,'v1','ACTIVE','unused')",
                    device,tenant,tenant,product,"same-code","Warmer");
        }
    }
    String desired(long version, String patch) { return "{\"version\":" + version + ",\"desired\":" + patch + "}"; }
    void report(String tenant, String device, String id, Instant time, Map<String,Value> values) throws Exception {
        var handler = new EventPublishingDeviceMessageHandler(m -> CompletableFuture.completedFuture(null), bus);
        handler.handle(new DeviceMessage(new MessageId(id), new DeviceKey(new TenantId(tenant),new DeviceId(device)),
                "v1",time,NOW.plusSeconds(60),MessageOrigin.DEVICE,new DeviceMessageBody.PropertyReport(values)))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    @Test void arrayDesiredStateValidatesAndConvergesWithReports() throws Exception {
        for (var invalid : List.of("[]", "[101]", "[\"1\"]", "[1,2,3,4]", "[[1]]"))
            mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                .content(desired(0,"{\"samples\":"+invalid+"}"))).andExpect(status().isBadRequest());
        mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
            .content(desired(0,"{\"samples\":[1,2]}"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.desired.samples.value[1]").value(2));
        report("ta","d1","array1",NOW,Map.of("samples",new Value.ArrayValue(List.of(
            new Value.DecimalValue(new java.math.BigDecimal("1.0")),new Value.IntegerValue(2)))));
        mvc.perform(get(A).with(user("alice"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.reported.samples.value[0]").value(1.0)).andExpect(jsonPath("$.delta").isEmpty());
    }    @Test void desiredReportedDeltaAndPartialRemovalRoundTripThroughIngress() throws Exception {
        mvc.perform(get(A).with(user("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.reported").isEmpty());
        mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                .content(desired(0,"{\"power\":true,\"level\":70}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.delta.level").value(70));
        report("ta","d1","m1",NOW,Map.of("power",new Value.BooleanValue(true)));
        report("ta","d1","m2",NOW.plusSeconds(1),Map.of("level",new Value.IntegerValue(70)));
        mvc.perform(get(A).with(user("alice"))).andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.delta").isEmpty()).andExpect(jsonPath("$.reported.power.value").value(true))
                .andExpect(jsonPath("$.reported.level.messageId").value("m2"))
                .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                .content(desired(3,"{\"power\":null}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.desired.power").doesNotExist()).andExpect(jsonPath("$.desired.level.value").value(70));
        mvc.perform(get(B).with(user("bob"))).andExpect(jsonPath("$.version").value(0));
    }
    @Test void duplicateAndOldReportsDoNotOverwriteButPartialFieldsMerge() throws Exception {
        report("ta","d1","new",NOW.plusSeconds(10),Map.of("power",new Value.BooleanValue(true)));
        report("ta","d1","new",NOW.plusSeconds(10),Map.of("power",new Value.BooleanValue(false)));
        report("ta","d1","old",NOW,Map.of("power",new Value.BooleanValue(false),"level",new Value.IntegerValue(30)));
        mvc.perform(get(A).with(user("alice"))).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reported.power.value").value(true)).andExpect(jsonPath("$.reported.level.value").value(30));
        assertThrows(ExecutionException.class,()->report("ta","d1","bad",NOW.plusSeconds(20),Map.of("level",new Value.IntegerValue(101))));
        mvc.perform(get(A).with(user("alice"))).andExpect(jsonPath("$.version").value(2));
    }
    @Test void scopeRbacMembershipAndCsrfAreEnforcedBeforeRedis() throws Exception {
        mvc.perform(get(A)).andExpect(status().isUnauthorized());
        mvc.perform(get(A).with(user("bob"))).andExpect(status().isForbidden());
        mvc.perform(get(A.replace("d1","d2")).with(user("alice"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/management/catalog/platform/devices/d1/shadow").with(user("root"))).andExpect(status().isOk());
        mvc.perform(get("/api/management/catalog/platform/devices/d1/shadow").with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(put(A+"/desired").with(user("viewer")).with(csrf()).contentType("application/json")
                .content(desired(0,"{\"power\":true}"))).andExpect(status().isForbidden());
        mvc.perform(put(A+"/desired").with(user("alice")).contentType("application/json")
                .content(desired(0,"{\"power\":true}"))).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM identity_role_permission WHERE role_id='r' AND permission='device:write'");
        mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                .content(desired(0,"{\"power\":true}"))).andExpect(status().isForbidden());
        jdbc.update("UPDATE tenant_membership SET enabled=FALSE WHERE account_id='a'");
        mvc.perform(get(A).with(user("alice"))).andExpect(status().isForbidden());
        assertTrue(store.documents.isEmpty());
    }
    @Test void validatesModelAndVersionAndRejectsDisabledDevices() throws Exception {
        for (String patch : List.of("{\"unknown\":1}","{\"temperature\":22}","{\"secret\":\"a\"}",
                "{\"level\":101}","{\"power\":1}","{\"level\":1.5}","{\"power\":{}}")) {
            mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                    .content(desired(0,patch))).andExpect(status().isBadRequest());
        }
        mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                .content(desired(0,"{\"power\":true}"))).andExpect(status().isOk());
        mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                .content(desired(0,"{\"power\":false}"))).andExpect(status().isConflict());
        jdbc.update("UPDATE device_registration SET lifecycle='DISABLED' WHERE id='d1'");
        mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                .content(desired(1,"{\"power\":false}"))).andExpect(status().isConflict());
    }
    @Test void redisFailureReturns503AndDoesNotAcknowledgeReports() throws Exception {
        store.unavailable = true;
        mvc.perform(get(A).with(user("alice"))).andExpect(status().isServiceUnavailable());
        mvc.perform(put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                .content(desired(0,"{\"power\":true}"))).andExpect(status().isServiceUnavailable());
        assertThrows(ExecutionException.class,()->report("ta","d1","m1",NOW,Map.of("power",new Value.BooleanValue(true))));
        store.unavailable=false;
        mvc.perform(get(A).with(user("alice"))).andExpect(jsonPath("$.version").value(0));
    }
    @Test void futureClocksDisabledRegistrationsAndNonReportablePropertiesAreRejected() throws Exception {
        assertThrows(ExecutionException.class,()->report("ta","d1","future",NOW.plusSeconds(361),Map.of("power",new Value.BooleanValue(true))));
        assertThrows(ExecutionException.class,()->report("ta","d1","secret",NOW,Map.of("secret",new Value.TextValue("hidden"))));
        jdbc.update("UPDATE device_registration SET lifecycle='DISABLED' WHERE id='d1'");
        assertThrows(ExecutionException.class,()->report("ta","d1","disabled",NOW,Map.of("power",new Value.BooleanValue(true))));
        assertTrue(store.documents.isEmpty());
    }
    @Test void concurrentDesiredUpdatesHaveExactlyOneWinner() throws Exception {
        var executor=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try {
            Callable<Integer> write=()->{ready.countDown();start.await();return mvc.perform(
                    put(A+"/desired").with(user("alice")).with(csrf()).contentType("application/json")
                            .content(desired(0,"{\"power\":true}"))).andReturn().getResponse().getStatus();};
            var first=executor.submit(write);var second=executor.submit(write);
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            assertEquals(Set.of(200,409),Set.of(first.get(),second.get()));
        } finally { executor.shutdownNow(); }
    }
}

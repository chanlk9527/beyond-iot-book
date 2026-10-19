package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.bootstrap.message.EventPublishingDeviceMessageHandler;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.device.api.ProductHistoryPolicy;
import com.nebula.iot.device.application.DeviceHistoryService;
import com.nebula.iot.message.EventBus;
import com.nebula.iot.storage.*;
import com.nebula.iot.storage.HistoryStore.*;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver",
        "DATABASE_PASSWORD=test","nebula.redis.enabled=false","nebula.history.cleanup-interval-ms=3600000"})
@ActiveProfiles("management") @AutoConfigureMockMvc @Import(DeviceHistoryApiTest.Stores.class)
class DeviceHistoryApiTest {
    @TestConfiguration static class Stores { @Bean MemoryHistory testHistoryStore(){return new MemoryHistory();} }
    static class MemoryHistory implements HistoryStore {
        final Map<Series,List<Observation>> data=new HashMap<>(); int failAfter=-1; boolean unavailable;
        public Capabilities capabilities(){return new Capabilities("test",true,true,false,false,false,false,3650);}
        public synchronized void append(List<Observation> observations){
            for(var o:observations){
                var rows=data.computeIfAbsent(o.series(),k->new ArrayList<>());
                rows.removeIf(v->v.occurredAt().equals(o.occurredAt())&&v.messageId().equals(o.messageId()));rows.add(o);
                if(failAfter==0){failAfter=-1;throw new HistoryStorageException(new IllegalStateException("test failure"));}
                if(failAfter>0)failAfter--;
            }
        }
        public synchronized Page query(Query q){
            if(unavailable) throw new HistoryStorageException(new IllegalStateException("private connection details"));
            var sorted=data.getOrDefault(q.series(),List.of()).stream().filter(o->!o.occurredAt().isBefore(q.from())&&o.occurredAt().isBefore(q.to()))
                    .filter(o->q.before()==null||o.occurredAt().isBefore(q.before().occurredAt())||o.occurredAt().equals(q.before().occurredAt())&&o.messageId().compareTo(q.before().messageId())<0)
                    .sorted(Comparator.comparing(Observation::occurredAt).thenComparing(Observation::messageId).reversed()).limit(q.limit()+1).toList();
            boolean more=sorted.size()>q.limit();var rows=sorted.subList(0,Math.min(sorted.size(),q.limit()));
            return new Page(rows,more?new Cursor(rows.getLast().occurredAt(),rows.getLast().messageId()):null);
        }
        public synchronized void deleteBefore(Series s,Instant cutoff){data.getOrDefault(s,new ArrayList<>()).removeIf(o->o.occurredAt().isBefore(cutoff));}
        long count(){return data.values().stream().mapToLong(List::size).sum();}
    }
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired DeviceHistoryService history; @Autowired MemoryHistory storage; @Autowired EventBus bus;
    Instant now;
    static final String POLICY="/api/management/catalog/tenants/ta/products/pa/data-policy";
    @BeforeEach void seed(){
        storage.data.clear();storage.failAfter=-1;storage.unavailable=false;now=Instant.ofEpochMilli(Instant.now().minusSeconds(60).toEpochMilli());
        for(String table:List.of("device_history_inbox","device_history_series","device_history_policy","device_management_audit","device_registration","device_model_version","device_product",
                "identity_account_role","identity_role_permission","identity_role","tenant_membership","identity_account","tenant"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','A'),('tb','B')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'tb'),('v','viewer','unused',FALSE,'ta')");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','a','OWNER'),('tb','b','OWNER'),('ta','v','VIEWER')");
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('r','history','History')");
        for(String p:List.of("product:read","product:write","device:read"))jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('r',?)",p);
        for(String id:List.of("a","b","v"))jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES (?,'r')",id);
        String model="""
            {"properties":{"temperature":{"id":"temperature","schema":{"type":"DECIMAL"},"access":"READ_ONLY"},
            "power":{"id":"power","schema":{"type":"BOOLEAN"},"access":"READ_WRITE"}},
            "events":{"power":{"id":"power","fields":{"code":{"id":"code","schema":{"type":"TEXT"},"required":true}}}},"operations":{}}
            """;
        for(String t:List.of("ta","tb")){
            String p=t.equals("ta")?"pa":"pb", d=t.equals("ta")?"d1":"d2";
            jdbc.update("INSERT INTO device_product(id,tenant_id,scope_key,code,name,draft_model,latest_version) VALUES (?,?,?,?,?,?,'v1')",p,t,t,p,"Device",model);
            jdbc.update("INSERT INTO device_model_version(product_id,version,model_json) VALUES (?,'v1',?)",p,model);
            jdbc.update("INSERT INTO device_registration(id,tenant_id,scope_key,product_id,code,name,model_version,lifecycle,credential_hash) VALUES (?,?,?,?,?,?,'v1','ACTIVE','unused')",d,t,t,p,d,"Device");
        }
    }
    ProductHistoryPolicy.Rule rule(boolean latest,String mode,int days){return new ProductHistoryPolicy.Rule(latest,mode,days,100,"every",60,false,365);}
    ProductHistoryPolicy policy(){return new ProductHistoryPolicy(rule(true,"time",30),Map.of(),rule(false,"time",90),Map.of());}
    DeviceMessage message(String id,Instant at,DeviceMessageBody body){return new DeviceMessage(new MessageId(id),new DeviceKey(new TenantId("ta"),new DeviceId("d1")),"v1",at,now,MessageOrigin.DEVICE,body);}
    Map<String,Value> values(boolean full){var m=new HashMap<String,Value>();m.put("temperature",new Value.DecimalValue(new java.math.BigDecimal("25.1234567890123456789")));if(full)m.put("power",new Value.BooleanValue(true));return m;}
    Series series(Kind kind,String id){return new Series("ta","d1","pa",kind,id);}
    @Test void savesPoliciesWithIndependentRevisionAuthorizationAndCapabilityChecks() throws Exception {
        mvc.perform(get(POLICY).with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0));
        String input=json.writeValueAsString(Map.of("revision",0,"policy",policy()));
        mvc.perform(put(POLICY).with(user("alice")).contentType("application/json").content(input)).andExpect(status().isForbidden());
        mvc.perform(put(POLICY).with(user("viewer")).with(csrf()).contentType("application/json").content(input)).andExpect(status().isForbidden());
        mvc.perform(get(POLICY).with(user("bob"))).andExpect(status().isForbidden());
        mvc.perform(put(POLICY).with(user("alice")).with(csrf()).contentType("application/json").content(input)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(put(POLICY).with(user("alice")).with(csrf()).contentType("application/json").content(input)).andExpect(status().isConflict());
        var unsupported=new ProductHistoryPolicy(rule(true,"count",30),Map.of(),rule(false,"none",30),Map.of());
        mvc.perform(put(POLICY).with(user("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("revision",1,"policy",unsupported)))).andExpect(status().isUnprocessableEntity());
        assertEquals(0,jdbc.queryForObject("SELECT revision FROM device_product WHERE id='pa'",Integer.class));
    }
    @Test void normalizesPartialAndFullReportsWithoutFillingAbsentPropertiesAndKeepsCompleteEvents() throws Exception {
        history.savePolicy("alice","ta","pa",0,policy());
        var handler=new EventPublishingDeviceMessageHandler(m->CompletableFuture.completedFuture(null),bus);
        history.accept(message("first",now,new DeviceMessageBody.PropertyReport(values(true))));
        history.accept(message("second",now.plusMillis(1),new DeviceMessageBody.PropertyReport(values(false))));
        history.accept(message("second",now.plusMillis(1),new DeviceMessageBody.PropertyReport(values(false))));
        handler.handle(message("event",now,new DeviceMessageBody.EventReport("power",Map.of("code",new Value.TextValue("same named event"))))).toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertEquals(4,storage.count());
        assertEquals(1,storage.data.get(series(Kind.PROPERTY,"power")).size());
        assertEquals(2,storage.data.get(series(Kind.PROPERTY,"temperature")).size());
        assertEquals("25.1234567890123456789",storage.data.get(series(Kind.PROPERTY,"temperature")).getFirst().payload());
        assertEquals("{\"code\":\"same named event\"}",storage.data.get(series(Kind.EVENT,"power")).getFirst().payload());
        assertThrows(IllegalArgumentException.class,()->history.accept(message("first",now,new DeviceMessageBody.PropertyReport(Map.of("power",new Value.BooleanValue(false))))));
    }
    @Test void recoversPartialWritesWithFrozenDecisionsAfterPolicyChanges() {
        history.savePolicy("alice","ta","pa",0,policy());storage.failAfter=0;
        assertThrows(HistoryStorageException.class,()->history.accept(message("retry",now,new DeviceMessageBody.PropertyReport(values(true)))));
        assertEquals(1,storage.count());
        history.savePolicy("alice","ta","pa",1,ProductHistoryPolicy.initial());
        history.cleanup();assertEquals(2,storage.count());
        history.accept(message("retry",now,new DeviceMessageBody.PropertyReport(values(true))));assertEquals(2,storage.count());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_history_inbox WHERE completed=TRUE",Integer.class));
    }
    @Test void queriesStableCursorsAndEnforcesTenantAndRetentionBoundaries() throws Exception {
        history.savePolicy("alice","ta","pa",0,policy());
        history.accept(message("a",now,new DeviceMessageBody.PropertyReport(values(false))));
        history.accept(message("b",now,new DeviceMessageBody.PropertyReport(values(false))));
        var first=history.query("alice","ta","d1",Kind.PROPERTY,"temperature",now.minusSeconds(1),now.plusSeconds(1),null,1);
        assertEquals("b",first.items().getFirst().messageId());assertNotNull(first.nextCursor());
        var second=history.query("alice","ta","d1",Kind.PROPERTY,"temperature",now.minusSeconds(1),now.plusSeconds(1),first.nextCursor(),1);
        assertEquals("a",second.items().getFirst().messageId());assertNull(second.nextCursor());
        String endpoint="/api/management/catalog/tenants/ta/devices/d1/history/PROPERTY/temperature";
        mvc.perform(get(endpoint).param("from",now.minusSeconds(1).toString()).param("to",now.plusSeconds(1).toString()).with(user("bob"))).andExpect(status().isForbidden());
        mvc.perform(get(endpoint).param("from",now.minusSeconds(1).toString()).param("to",now.plusSeconds(1).toString()).param("cursor","broken").with(user("alice"))).andExpect(status().isBadRequest());
        var old=new Observation(series(Kind.PROPERTY,"temperature"),now.minus(Duration.ofDays(31)),"old",now,"v1","DECIMAL","20");
        storage.append(List.of(old));history.cleanup();assertFalse(storage.data.get(old.series()).contains(old));
    }
    @Test void latestAndHistoryAreIndependentAndSourcesAreValidated() {
        var p=new ProductHistoryPolicy(rule(true,"time",30),Map.of("temperature",rule(false,"time",30)),rule(false,"none",30),Map.of());
        history.savePolicy("alice","ta","pa",0,p);
        var message=message("latest",now,new DeviceMessageBody.PropertyReport(values(true)));
        var filtered=(DeviceMessageBody.PropertyReport)history.filterLatest(message).body();
        assertEquals(Set.of("power"),filtered.properties().keySet());
        history.accept(message);assertEquals(2,storage.count());
        assertThrows(IllegalArgumentException.class,()->history.accept(message("invalid",now,new DeviceMessageBody.EventReport("power",Map.of()))));
        jdbc.update("UPDATE device_registration SET lifecycle='DISABLED' WHERE id='d1'");
        assertThrows(IllegalArgumentException.class,()->history.accept(message("disabled",now,new DeviceMessageBody.PropertyReport(values(false)))));
    }
    @Test void reportsStorageOutagesWithoutLeakingConnectionDetails() throws Exception {
        storage.unavailable=true;
        mvc.perform(get("/api/management/catalog/tenants/ta/devices/d1/history/PROPERTY/temperature")
                .param("from",now.minusSeconds(1).toString()).param("to",now.plusSeconds(1).toString()).with(user("alice")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("History storage is unavailable"));
    }
}

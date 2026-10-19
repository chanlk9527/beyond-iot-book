package com.nebula.iot;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:production;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test"})
@ActiveProfiles("management") @AutoConfigureMockMvc
class ProductionApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    static final String A="/api/management/catalog/tenants/ta", B="/api/management/catalog/tenants/tb", PLATFORM="/api/management/catalog/platform";
    static final List<String> PERMISSIONS=List.of("product:read","product:write","product:publish","device:read","device:write","device:credential","production:read","production:write","production:credential");
    String product;
    @BeforeEach void seed() throws Exception {
        for(String table:List.of("device_production_event","device_production_item","device_production_batch","device_management_audit","device_registration","device_model_version","device_product","identity_account_role","identity_role_permission","identity_role","tenant_membership","identity_account","tenant")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','A'),('tb','B')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('root','root','unused',TRUE,NULL),('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'tb'),('v','viewer','unused',FALSE,'ta'),('r','reader','unused',FALSE,'ta')");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','a','OWNER'),('tb','b','ADMIN'),('ta','v','VIEWER'),('ta','r','ADMIN')");
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('business','business','Business'),('reader','reader','Reader')");
        for(String p:PERMISSIONS) jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('business',?)",p);
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('reader','production:read')");
        for(String id:List.of("a","b","v")) jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES (?,'business')",id);
        jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES ('r','reader')");
        var created=call(post(A+"/products"),"alice",Map.of("code","warmer","name","Warmer","description","Production test"),201);
        product=created.path("id").asText();
        call(post(A+"/products/"+product+"/publish"),"alice",Map.of("revision",0,"confirmEmpty",true),200);
    }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r,String actor,Object value) throws Exception {
        return r.with(user(actor)).with(csrf()).contentType("application/json").content(json.writeValueAsString(value));
    }
    JsonNode call(MockHttpServletRequestBuilder r,String actor,Object value,int expected) throws Exception {
        var response=mvc.perform(body(r,actor,value)).andExpect(status().is(expected)).andReturn().getResponse();
        return response.getContentAsString().isBlank()?json.nullNode():json.readTree(response.getContentAsString());
    }
    Map<String,Object> creation(String code,int quantity) { return Map.of("code",code,"name","Batch "+code,"productId",product,"modelVersion","v1","quantity",quantity,"hardwareVersion","hw1","firmwareVersion","1.0.0"); }
    JsonNode batch(String code,int quantity) throws Exception { return call(post(A+"/production/batches"),"alice",creation(code,quantity),201); }
    String path(JsonNode detail) { return A+"/production/batches/"+detail.path("batch").path("id").asText(); }
    long revision(JsonNode d) { return d.path("batch").path("revision").asLong(); }
    JsonNode generate(JsonNode d) throws Exception { return call(post(path(d)+"/generate"),"alice",Map.of("revision",revision(d)),200); }
    JsonNode evidence(JsonNode d,String id,String kind,String result,int status) throws Exception {
        return call(post(path(d)+"/devices/"+id+"/"+kind),"alice",Map.of("revision",revision(d),"credentialVersion",1,"station","bench-1","note","Temperature, power and credential checked","result",result),status);
    }
    JsonNode passAll(JsonNode d) throws Exception {
        var ids=new ArrayList<String>();d.path("items").forEach(i->ids.add(i.path("deviceId").asText()));
        for(String id:ids) { d=evidence(d,id,"burn","",200);d=evidence(d,id,"tests","PASS",200); } return d;
    }
    JsonNode release(JsonNode d,int status) throws Exception { return call(post(path(d)+"/release"),"alice",Map.of("revision",revision(d),"note","All manufacturing checks passed"),status); }
    JsonNode deliver(JsonNode d,int status) throws Exception { return call(post(path(d)+"/deliver"),"alice",Map.of("revision",revision(d),"recipient","Warehouse A","deliveryRef","DN-100","note","Sealed"),status); }
    @Test void fullFlowExportsCredentialsAndKeepsDeliveryAndDeviceOwnershipSeparate() throws Exception {
        var d=generate(batch("production-a",2));assertEquals(2,d.path("items").size());assertFalse(d.toString().contains("secret"));
        var export=mvc.perform(body(post(path(d)+"/credentials"),"alice",Map.of("revision",revision(d)))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        var credentials=json.readTree(export.getContentAsString());assertEquals(2,credentials.size());
        for(var c:credentials) {
            assertEquals(c.path("deviceId").asText(),c.path("clientId").asText());assertFalse(c.path("secret").asText().isBlank());
            assertEquals("ta",jdbc.queryForObject("SELECT tenant_id FROM device_registration WHERE id=?",String.class,c.path("deviceId").asText()));
        }
        d=release(passAll(d),200);assertEquals("RELEASED",d.path("batch").path("status").asText());
        d=deliver(d,200);assertEquals("DELIVERED",d.path("batch").path("status").asText());assertEquals("Warehouse A",d.path("batch").path("recipient").asText());
        assertEquals(9,d.path("totalEvents").asInt());
        assertFalse(d.toString().contains(credentials.get(0).path("secret").asText()));
        evidence(d,d.path("items").get(0).path("deviceId").asText(),"tests","FAIL",409);
        call(post(path(d)+"/credentials"),"alice",Map.of("revision",revision(d)),409);
        deliver(d,409);
    }
    @Test void generationIsAtomicOnCollisionAndCannotBeRepeated() throws Exception {
        var d=batch("collision",2);
        call(post(A+"/devices"),"alice",Map.of("productId",product,"code","collision-0002","name","existing","modelVersion","v1"),201);
        call(post(path(d)+"/generate"),"alice",Map.of("revision",0),409);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_registration WHERE code='collision-0001'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_production_item",Integer.class));
        assertEquals("PLANNED",jdbc.queryForObject("SELECT status FROM device_production_batch",String.class));
        var generated=generate(batch("unique",1));
        call(post(path(generated)+"/generate"),"alice",Map.of("revision",0),409);
        call(post(path(generated)+"/generate"),"alice",Map.of("revision",revision(generated)),409);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM device_registration",Integer.class));
    }
    @Test void concurrentGenerationOnlyCreatesOneBatchOfDevices() throws Exception {
        var d=batch("concurrent",1);String path=path(d)+"/generate";
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt=()->{start.await();return mvc.perform(body(post(path),"alice",Map.of("revision",0))).andReturn().getResponse().getStatus();};
            var first=pool.submit(attempt);var second=pool.submit(attempt);start.countDown();
            assertEquals(Set.of(200,409),Set.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_production_item",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void testingRequiresCurrentBurnAndFailuresBlockReleaseWithReworkHistoryRetained() throws Exception {
        var d=generate(batch("test",1));String id=d.path("items").get(0).path("deviceId").asText();
        evidence(d,id,"tests","PASS",409);release(d,409);deliver(d,409);
        d=evidence(d,id,"burn","",200);d=evidence(d,id,"tests","FAIL",200);release(d,409);
        d=evidence(d,id,"tests","PASS",200);d=release(d,200);
        d=evidence(d,id,"burn","",200);assertEquals("PRODUCING",d.path("batch").path("status").asText());assertTrue(d.path("items").get(0).path("testResult").isNull());release(d,409);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_production_event WHERE result='FAIL'",Integer.class));
    }
    @Test void credentialRotationInvalidatesReleaseEvidenceAndDeliveryUntilRetested() throws Exception {
        var d=release(passAll(generate(batch("rotate",1))),200);var item=d.path("items").get(0);String id=item.path("deviceId").asText();
        call(post(A+"/devices/"+id+"/credential"),"alice",Map.of("revision",0),200);
        deliver(d,409);evidence(d,id,"burn","",409);
        d=call(post(path(d)+"/devices/"+id+"/burn"),"alice",Map.of("revision",revision(d),"credentialVersion",2,"station","bench","note","New secret burned"),200);
        d=call(post(path(d)+"/devices/"+id+"/tests"),"alice",Map.of("revision",revision(d),"credentialVersion",2,"station","bench","note","Rechecked","result","PASS"),200);
        deliver(release(d,200),200);
    }
    @Test void permissionsTenantBoundariesCsrfAndRevisionsAreEnforced() throws Exception {
        var d=batch("secure",1);String id=d.path("batch").path("id").asText();
        mvc.perform(get(path(d))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(d)).with(user("reader"))).andExpect(status().isOk());
        call(post(path(d)+"/generate"),"reader",Map.of("revision",0),403);
        call(post(path(d)+"/generate"),"viewer",Map.of("revision",0),403);
        mvc.perform(post(path(d)+"/generate").with(user("alice")).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isForbidden());
        mvc.perform(get(B+"/production/batches/"+id).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(PLATFORM+"/production/batches/"+id).with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(get(PLATFORM+"/production/batches/"+id).with(user("root"))).andExpect(status().isOk());
        d=generate(d);
        jdbc.update("DELETE FROM identity_role_permission WHERE permission='production:credential'");
        call(post(path(d)+"/credentials"),"alice",Map.of("revision",revision(d)),403);
        call(post(path(d)+"/release"),"alice",Map.of("revision",0,"note","stale"),409);
        jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='ta'");
        mvc.perform(get(path(d)).with(user("alice"))).andExpect(status().isForbidden());
    }
    @Test void productionMenuGrantsRegisteredCapabilitiesAndDisabledResourcesBlockDelivery() throws Exception {
        var role=call(post("/api/management/iam/roles"),"root",Map.of("code","factory","name","Factory","menus",List.of("page:production")),201);
        var permissions=json.convertValue(role.path("permissions"),List.class);
        assertTrue(permissions.containsAll(List.of("page:production","production:read","production:write","production:credential","product:read","device:write","device:credential")));
        assertFalse(permissions.contains("page:devices"));
        var d=release(passAll(generate(batch("disabled",1))),200);
        call(put(A+"/products/"+product+"/status"),"alice",Map.of("revision",1,"enabled",false),200);
        deliver(d,409);
        call(put(A+"/products/"+product+"/status"),"alice",Map.of("revision",2,"enabled",true),200);
        jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='ta'");
        call(post(PLATFORM+"/production/batches/"+d.path("batch").path("id").asText()+"/deliver"),"root",Map.of("revision",revision(d),"recipient","Warehouse","deliveryRef","DN-2","note",""),409);
    }
    @Test void missingDeviceCredentialPermissionRollsBackGenerationAndBlocksExport() throws Exception {
        var d=batch("permissions",1);
        jdbc.update("DELETE FROM identity_role_permission WHERE permission='device:credential'");
        call(post(path(d)+"/generate"),"alice",Map.of("revision",0),403);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_production_item",Integer.class));
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('business','device:credential')");
        d=generate(d);
        jdbc.update("DELETE FROM identity_role_permission WHERE permission='device:credential'");
        call(post(path(d)+"/credentials"),"alice",Map.of("revision",revision(d)),403);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_production_event WHERE action='CREDENTIALS_EXPORTED'",Integer.class));
    }
    @Test void invalidInputsAndForeignDevicesAreRejected() throws Exception {
        var invalid=new HashMap<String,Object>(creation("invalid",51));call(post(A+"/production/batches"),"alice",invalid,400);
        invalid.put("quantity",1);invalid.put("modelVersion","v99");call(post(A+"/production/batches"),"alice",invalid,400);
        var d=generate(batch("bounds",1));batchDuplicate();
        evidence(d,"unrelated","burn","",404);
        call(post(path(d)+"/devices/"+d.path("items").get(0).path("deviceId").asText()+"/burn"),"alice",Map.of("revision",revision(d),"credentialVersion",1,"station","","note",""),400);
        mvc.perform(get(path(d)).param("eventPage","0").with(user("alice"))).andExpect(status().isBadRequest());
    }
    void batchDuplicate() throws Exception { call(post(A+"/production/batches"),"alice",creation("bounds",1),409); }
    @Test void traceabilityBlocksDeletingProductsAndManufacturedDevices() throws Exception {
        var planned=batch("trace",1);
        call(put(A+"/products/"+product+"/status"),"alice",Map.of("revision",1,"enabled",false),200);
        call(delete(A+"/products/"+product).param("revision","2"),"alice",null,409);
        call(put(A+"/products/"+product+"/status"),"alice",Map.of("revision",2,"enabled",true),200);
        var d=generate(planned);String device=d.path("items").get(0).path("deviceId").asText();
        call(put(A+"/devices/"+device),"alice",Map.of("name","Disabled","lifecycle","DISABLED","revision",0),200);
        call(delete(A+"/devices/"+device).param("revision","1"),"alice",null,409);
    }
}

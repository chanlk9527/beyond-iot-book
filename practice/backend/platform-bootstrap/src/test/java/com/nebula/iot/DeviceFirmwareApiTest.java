package com.nebula.iot;

import com.fasterxml.jackson.databind.*;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.device.application.DeviceFirmwareService;
import com.nebula.iot.message.*;
import java.time.*;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:device-firmware;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test"})
@ActiveProfiles("management") @AutoConfigureMockMvc
class DeviceFirmwareApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired DeviceFirmwareService service; @Autowired EventBus bus;
    static final String A="/api/management/catalog/tenants/ta",B="/api/management/catalog/tenants/tb",PLATFORM="/api/management/catalog/platform";
    String product,device,base,target,channels;Instant now;
    @BeforeEach void seed() throws Exception {
        for (String table:List.of("device_firmware_report","device_firmware_inventory","device_firmware_version","device_firmware_file","device_firmware_channel","device_management_audit","device_registration","device_model_version","device_product","identity_account_role","identity_role_permission","identity_role","tenant_membership","identity_account","tenant")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','A'),('tb','B')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('root','root','unused',TRUE,NULL),('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'tb'),('r','reader','unused',FALSE,'ta')");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','a','OWNER'),('tb','b','ADMIN'),('ta','r','VIEWER')");
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('business','business','Business'),('reader','reader','Reader')");
        for (String permission:List.of("product:read","product:write","product:publish","device:read","device:write","device:credential")) jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('business',?)",permission);
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('reader','device:read')");
        jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES ('a','business'),('b','business'),('r','reader')");
        product=call(post(A+"/products"),"alice",Map.of("code","warmer","name","Warmer","description",""),201).path("id").asText();
        call(post(A+"/products/"+product+"/publish"),"alice",Map.of("revision",0,"confirmEmpty",true),200);
        device=call(post(A+"/devices"),"alice",Map.of("productId",product,"code","device","name","Device","modelVersion","v1"),201).path("device").path("id").asText();
        base=A+"/devices/"+device+"/firmware";channels=A+"/products/"+product+"/firmware/channels";
        call(post(channels),"alice",Map.of("channel",0,"component","main","name","Main"),201);
        target=createTarget(0,"1.10.0",List.of("1.9.0"));now=Instant.now().minusSeconds(10);
    }
    JsonNode call(MockHttpServletRequestBuilder request,String actor,Object value,int status) throws Exception {
        var response=mvc.perform(request.with(user(actor)).with(csrf()).contentType("application/json").content(json.writeValueAsString(value))).andExpect(status().is(status)).andReturn().getResponse();
        return response.getContentAsString().isBlank()?json.nullNode():json.readTree(response.getContentAsString());
    }
    String createTarget(int channel,String version,List<String> sources) throws Exception {
        var artifact=Map.of("url","https://example.com/firmware.bin","size",100,"sha256","a".repeat(64),"keyId","factory","signature",Base64.getEncoder().encodeToString(new byte[64]));
        var v=call(post(channels+"/"+channel+"/versions"),"alice",Map.of("version",version,"description","Update","sourceVersions",sources,"hardwareVersions",List.of("HW-A"),"artifact",artifact),201);
        String id=v.path("id").asText();call(put(channels+"/"+channel+"/versions/"+id+"/status"),"alice",Map.of("enabled",true,"revision",0),200);return id;
    }
    DeviceMessage message(String id,Instant received,DeviceMessageBody body) {
        return new DeviceMessage(new MessageId(id),new DeviceKey(new TenantId("ta"),new DeviceId(device)),"v1",received.minusSeconds(1),received,MessageOrigin.DEVICE,body,Optional.empty(),Optional.empty(),new ProductId(product));
    }
    DeviceMessage info(String id,Instant time,String version,String hardware,String boot) { return message(id,time,new DeviceMessageBody.DeviceInfoReport(version,hardware,boot,Optional.of("sdk1"),false)); }
    void report(String id,Instant time,Map<String,String> versions) { service.accept(message(id,time,new DeviceMessageBody.OtaVersionReport(versions))); }
    JsonNode inventory() throws Exception { return call(get(base),"alice",null,200); }
    JsonNode preflight(int channel,String id) throws Exception { return call(get(base+"/channels/"+channel+"/versions/"+id+"/preflight"),"alice",null,200); }
    Set<String> reasons(JsonNode value) { var set=new HashSet<String>();value.path("reasons").forEach(r->set.add(r.path("code").asText()));return set; }
    @Test void emptyInventoryDoesNotUseProductOrProductionPlanDefaults() throws Exception {
        var inventory=inventory();assertTrue(inventory.path("information").isNull());assertTrue(inventory.path("components").isEmpty());
        var check=preflight(0,target);assertFalse(check.path("compatible").asBoolean());assertTrue(reasons(check).containsAll(Set.of("INFO_MISSING","COMPONENT_MISSING")));
    }
    @Test void listInventoryBatchChecksEveryDeviceAndDoesNotLeakAcrossTenants() throws Exception {
        service.accept(info("info",now,"1.9.0","HW-A","boot-1"));
        var result=call(get(A+"/devices/firmware-inventory").param("ids",device),"reader",null,200);
        assertEquals("1.9.0",result.path(device).path("components").get(0).path("version").asText());
        call(get(B+"/devices/firmware-inventory").param("ids",device),"bob",null,404);
        call(get(PLATFORM+"/devices/firmware-inventory").param("ids",device),"alice",null,403);
        call(get(PLATFORM+"/devices/firmware-inventory").param("ids",device),"root",null,200);
        call(get(A+"/devices/firmware-inventory").param("ids",device+",missing"),"alice",null,404);
        call(get(A+"/devices/firmware-inventory").param("ids",device+","+device),"alice",null,400);
        call(get(A+"/devices/firmware-inventory").param("ids",String.join(",",java.util.Collections.nCopies(101,device))),"alice",null,400);
        jdbc.update("DELETE FROM device_firmware_inventory");
        result=call(get(A+"/devices/firmware-inventory").param("ids",device),"reader",null,200);
        assertTrue(result.path(device).path("components").isEmpty());
    }
    @Test void trustedEventSubscriptionsPersistInfoAndOtaSnapshots() throws Exception {
        var info=info("info-1",now,"1.9.0","HW-A","boot-1");var report=message("versions-1",now.plusSeconds(1),new DeviceMessageBody.OtaVersionReport(Map.of("main","1.9.0","mcu","2.0.0")));
        var handler=new com.nebula.iot.bootstrap.message.EventPublishingDeviceMessageHandler(m->CompletableFuture.completedFuture(null),bus);
        handler.handle(info).toCompletableFuture().get(5,TimeUnit.SECONDS);handler.handle(report).toCompletableFuture().get(5,TimeUnit.SECONDS);
        var snapshot=inventory();assertEquals(2,snapshot.path("revision").asLong());assertEquals("HW-A",snapshot.path("information").path("hardwareVersion").asText());assertEquals(2,snapshot.path("components").size());
        var check=preflight(0,target);assertTrue(check.path("compatible").asBoolean());assertEquals("1.9.0",check.path("currentVersion").asText());assertEquals(2,check.path("inventoryRevision").asLong());
        assertTrue(check.path("warnings").toString().contains("EXTERNAL_FILE_UNVERIFIED"));assertTrue(check.path("warnings").toString().contains("DISPATCH_CHECKS_REQUIRED"));
    }
    @Test void replayAndLateEventsCannotRefreshOrRollBackInventoryAndConflictingIdsFail() throws Exception {
        var first=info("info-1",now,"1.9.0","HW-A","boot-1");service.accept(first);service.accept(first);
        service.accept(new DeviceMessage(first.messageId(),first.device(),first.modelVersion(),first.occurredAt(),now.plusSeconds(1),first.origin(),first.body()));
        assertEquals(1,inventory().path("revision").asLong());
        assertThrows(IllegalArgumentException.class,()->service.accept(info("info-1",now,"1.8.0","HW-A","boot-1")));
        service.accept(info("older",now.minusSeconds(5),"0.1.0","OLD","old-boot"));
        assertEquals("HW-A",inventory().path("information").path("hardwareVersion").asText());assertEquals(1,inventory().path("revision").asLong());
        report("versions-new",now.plusSeconds(3),Map.of("main","1.10.0"));report("versions-old",now.plusSeconds(2),Map.of("main","1.0.0"));
        assertEquals("1.10.0",inventory().path("components").get(0).path("version").asText());assertTrue(reasons(preflight(0,target)).contains("ALREADY_INSTALLED"));
    }
    @Test void rebootMakesOlderComponentReportsIneligibleUntilReReported() throws Exception {
        call(post(channels),"alice",Map.of("channel",9,"component","mcu","name","MCU"),201);String mcu=createTarget(9,"2.0.0",List.of("1.0.0"));
        service.accept(info("boot-old",now,"1.9.0","HW-A","boot-1"));report("mcu-old",now.plusSeconds(1),Map.of("mcu","1.0.0"));
        assertTrue(preflight(9,mcu).path("compatible").asBoolean());
        service.accept(info("boot-new",now.plusSeconds(2),"1.9.0","HW-A","boot-2"));
        assertTrue(reasons(preflight(9,mcu)).contains("COMPONENT_BEFORE_BOOT"));
        report("mcu-new",now.plusSeconds(3),Map.of("mcu","1.0.0"));assertTrue(preflight(9,mcu).path("compatible").asBoolean());
        service.accept(info("same-boot",now.plusSeconds(4),"1.9.0","HW-A","boot-2"));assertTrue(preflight(9,mcu).path("compatible").asBoolean());
    }
    @Test void sourceHardwareVersionRulesAndReportAgeProduceSpecificReasons() throws Exception {
        service.accept(info("old-info",now.minus(Duration.ofDays(2)),"bad-version","HW-B","boot-1"));
        var check=preflight(0,target);assertTrue(reasons(check).containsAll(Set.of("INFO_STALE","COMPONENT_STALE","VERSION_INVALID","SOURCE_NOT_ALLOWED","HARDWARE_INCOMPATIBLE")));
        service.accept(info("new-info",now,"1.8.0","HW-A","boot-1"));assertEquals(Set.of("SOURCE_NOT_ALLOWED"),reasons(preflight(0,target)));
        service.accept(info("compatible",now.plusSeconds(1),"1.9.0","HW-A","boot-1"));assertTrue(preflight(0,target).path("compatible").asBoolean());
    }
    @Test void opaqueVersionsRequireOnlyExplicitSourceMembership() throws Exception {
        call(post(channels),"alice",Map.of("channel",10,"component","radio","name","Radio","versionRule","OPAQUE"),201);
        String radio=createTarget(10,"A7_R12",List.of("build-2026"));service.accept(info("info",now,"1.9.0","HW-A","boot-1"));
        report("radio",now.plusSeconds(1),Map.of("radio","build-2026"));assertTrue(preflight(10,radio).path("compatible").asBoolean());
        report("radio-bad",now.plusSeconds(2),Map.of("radio","A7_R11"));assertTrue(reasons(preflight(10,radio)).contains("SOURCE_NOT_ALLOWED"));
    }
    @Test void inactiveResourcesAndDisabledTargetsCannotPass() throws Exception {
        service.accept(info("info",now,"1.9.0","HW-A","boot-1"));
        call(put(channels+"/0/versions/"+target+"/status"),"alice",Map.of("enabled",false,"revision",1),200);assertTrue(reasons(preflight(0,target)).contains("TARGET_DISABLED"));
        jdbc.update("UPDATE device_registration SET lifecycle='DISABLED' WHERE id=?",device);assertTrue(reasons(preflight(0,target)).containsAll(Set.of("DEVICE_INACTIVE","ACCESS_INELIGIBLE")));
        assertThrows(IllegalArgumentException.class,()->service.accept(info("disabled",now.plusSeconds(1),"1.10.0","HW-A","boot-1")));
        jdbc.update("UPDATE device_registration SET lifecycle='ACTIVE' WHERE id=?",device);jdbc.update("UPDATE device_product SET enabled=FALSE WHERE id=?",product);
        assertTrue(reasons(preflight(0,target)).contains("PRODUCT_DISABLED"));
        jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='ta'");
        var check=call(get(PLATFORM+"/devices/"+device+"/firmware/channels/0/versions/"+target+"/preflight"),"root",null,200);assertTrue(reasons(check).contains("ACCESS_INELIGIBLE"));
    }
    @Test void apiPermissionsScopeAndTargetOwnershipAreEnforcedAndReportedStateHasNoWriteEndpoint() throws Exception {
        call(get(base),"reader",null,200);call(get(base+"/channels/0/versions/"+target+"/preflight"),"reader",null,403);
        mvc.perform(get(base)).andExpect(status().isUnauthorized());call(get(B+"/devices/"+device+"/firmware"),"bob",null,404);
        call(get(PLATFORM+"/devices/"+device+"/firmware"),"alice",null,403);call(get(PLATFORM+"/devices/"+device+"/firmware"),"root",null,200);
        call(get(base+"/channels/9/versions/"+target+"/preflight"),"alice",null,404);call(get(base+"/channels/0/versions/missing/preflight"),"alice",null,404);
        call(put(base),"alice",Map.of("version","forged"),405);
        var p=call(post(A+"/products"),"alice",Map.of("code","other","name","Other","description",""),201);
        String foreign=A+"/products/"+p.path("id").asText()+"/firmware/channels";
        call(post(foreign),"alice",Map.of("channel",0,"component","main","name","Other"),201);
        String previous=channels;channels=foreign;String other=createTarget(0,"2.0.0",List.of("1.9.0"));channels=previous;
        call(get(base+"/channels/0/versions/"+other+"/preflight"),"alice",null,404);
    }
    @Test void ingressBindsScopeProductAndModelAndBoundsReportFields() {
        var valid=info("valid",now,"1.9.0","HW-A","boot-1");
        var wrongProduct=new DeviceMessage(valid.messageId(),valid.device(),"v1",valid.occurredAt(),valid.receivedAt(),valid.origin(),valid.body(),Optional.empty(),Optional.empty(),new ProductId("other"));
        assertThrows(IllegalArgumentException.class,()->service.accept(wrongProduct));
        var wrongModel=new DeviceMessage(valid.messageId(),valid.device(),"v2",valid.occurredAt(),valid.receivedAt(),valid.origin(),valid.body());assertThrows(IllegalArgumentException.class,()->service.accept(wrongModel));
        var wrongScope=new DeviceMessage(valid.messageId(),new DeviceKey(new TenantId("tb"),new DeviceId(device)),"v1",valid.occurredAt(),valid.receivedAt(),valid.origin(),valid.body());service.accept(wrongScope);
        assertThrows(IllegalArgumentException.class,()->report("bad-component",now,Map.of("Bad Component","1.0.0")));
        assertThrows(IllegalArgumentException.class,()->service.accept(info("too-long",now,"x".repeat(101),"HW-A","boot-1")));
        assertThrows(IllegalArgumentException.class,()->service.accept(info("future",Instant.now().plusSeconds(600),"1.9.0","HW-A","boot-1")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_inventory",Integer.class));
    }
    @Test void concurrentFirstReportsSerializeAndSnapshotsRemoveOmittedComponents() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();service.accept(info("info",now,"1.9.0","HW-A","boot-1"));return true;});
            var b=pool.submit(()->{start.await();report("versions",now.plusSeconds(1),Map.of("main","1.9.0","mcu","1.0.0"));return true;});start.countDown();
            assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS));assertEquals(2,inventory().path("revision").asLong());
        } finally { pool.shutdownNow(); }
        report("snapshot",now.plusSeconds(2),Map.of("main","1.9.0"));assertEquals(1,inventory().path("components").size());
    }
    @Test void expiredReplayIsIgnoredAndDeletingDeviceCleansUpRuntimeState() throws Exception {
        service.accept(info("expired",now.minus(Duration.ofDays(8)),"1.0.0","OLD","old"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_report",Integer.class));
        service.accept(info("current",now,"1.9.0","HW-A","boot-1"));
        jdbc.update("UPDATE device_firmware_report SET received_at=?",now.minus(Duration.ofDays(8)).toEpochMilli());service.cleanup();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_report",Integer.class));
        call(put(A+"/devices/"+device),"alice",Map.of("name","Disabled","lifecycle","DISABLED","revision",0),200);
        call(delete(A+"/devices/"+device).param("revision","1"),"alice",null,204);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_inventory",Integer.class));
    }
}

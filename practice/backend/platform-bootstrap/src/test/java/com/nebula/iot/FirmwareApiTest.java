package com.nebula.iot;

import com.fasterxml.jackson.databind.*;
import com.nebula.iot.device.domain.FirmwareVersionRule;
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
import com.nebula.iot.file.*;
import java.io.*;
import java.net.URI;
import java.security.*;
import java.time.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:firmware;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test"})
@ActiveProfiles("management") @AutoConfigureMockMvc
@Import(FirmwareApiTest.StorageConfiguration.class)
class FirmwareApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    static final String A="/api/management/catalog/tenants/ta", B="/api/management/catalog/tenants/tb", PLATFORM="/api/management/catalog/platform";
    String product, base;
    static final KeyPair SIGNING=signing();
    static KeyPair signing() { try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void keys(DynamicPropertyRegistry properties) {
        properties.add("nebula.firmware.trusted-keys.factory-key",()->Base64.getEncoder().encodeToString(SIGNING.getPublic().getEncoded()));
        properties.add("nebula.files.maximum-upload-bytes",()->64);
    }
    @TestConfiguration(proxyBeanMethods=false) static class StorageConfiguration {
        @Bean TestStorage testStorage() { return new TestStorage(); }
    }
    static class TestStorage implements ObjectStorage {
        final Map<String,byte[]> objects=new HashMap<>(); int puts; Runnable onPut; boolean fail; boolean http;
        public StoredObject put(String key,StagedUpload upload,String type) {
            puts++;if (fail) throw new FileStorageException("upload",new IOException("unavailable"));
            try (var in=upload.open()) { objects.put(key,in.readAllBytes()); } catch (IOException e) { throw new UncheckedIOException(e); }
            if (onPut!=null) onPut.run();
            return new StoredObject(new Reference("test-bucket",key,"v1"),upload.size(),upload.sha256(),type);
        }
        public InputStream open(Reference ref) { return new ByteArrayInputStream(objects.get(ref.key())); }
        public void delete(Reference ref) { objects.remove(ref.key()); }
        public Download download(Reference ref,Duration lifetime) { return new Download(URI.create((http?"http":"https")+"://files.example.com/"+ref.key()+"?signature=temporary"),Instant.now().plus(lifetime)); }
        public void close() { }
    }
    @Autowired TestStorage storage;
    @BeforeEach void seed() throws Exception {
        for (String table:List.of("device_firmware_version","device_firmware_file","device_firmware_channel","device_management_audit","device_product","identity_account_role","identity_role_permission","identity_role","tenant_membership","identity_account","tenant")) jdbc.update("DELETE FROM "+table);
        storage.objects.clear();storage.puts=0;storage.onPut=null;storage.fail=false;storage.http=false;
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','A'),('tb','B')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('root','root','unused',TRUE,NULL),('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'tb'),('v','viewer','unused',FALSE,'ta'),('r','reader','unused',FALSE,'ta')");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','a','OWNER'),('tb','b','ADMIN'),('ta','v','VIEWER'),('ta','r','ADMIN')");
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('business','business','Business'),('reader','reader','Reader')");
        for (String p:List.of("product:read","product:write","product:publish")) jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('business',?)",p);
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('reader','product:read')");
        for (String id:List.of("a","b","v")) jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES (?,'business')",id);
        jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES ('r','reader')");
        product=call(post(A+"/products"),"alice",Map.of("code","warmer","name","Warmer","description",""),201).path("id").asText();
        base=A+"/products/"+product+"/firmware/channels";
    }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,String actor,Object value) throws Exception {
        return request.with(user(actor)).with(csrf()).contentType("application/json").content(json.writeValueAsString(value));
    }
    JsonNode call(MockHttpServletRequestBuilder request,String actor,Object value,int expected) throws Exception {
        var response=mvc.perform(body(request,actor,value)).andExpect(status().is(expected)).andReturn().getResponse();
        return response.getContentAsString().isBlank()?json.nullNode():json.readTree(response.getContentAsString());
    }
    void channel(int number,String component,String rule,int expected) throws Exception {
        call(post(base),"alice",Map.of("channel",number,"component",component,"name","Firmware","versionRule",rule),expected);
    }
    Map<String,Object> artifact() { return new HashMap<>(Map.of("url","https://firmware.example.com/main.bin","size",1024,"sha256","a".repeat(64),"keyId","factory-key","signature",Base64.getEncoder().encodeToString(new byte[64]))); }
    Map<String,Object> version() { return new HashMap<>(Map.of("version","1.10.0","description","Fix heating","sourceVersions",List.of("1.9.0","1.0.0"),"hardwareVersions",List.of("HW-A"),"artifact",artifact())); }
    JsonNode createVersion() throws Exception { return call(post(base+"/0/versions"),"alice",version(),201); }
    @Test void inventoryIsImmutableAndEnablingDoesNotCreateUpgradeTasksOrChangeProductConfiguration() throws Exception {
        channel(0,"main","TUYA",201); var v=createVersion();
        assertFalse(v.path("enabled").asBoolean()); assertEquals(0,v.path("revision").asLong());
        call(post(base+"/0/versions"),"alice",version(),409);
        v=call(put(base+"/0/versions/"+v.path("id").asText()+"/status"),"alice",Map.of("enabled",true,"revision",0),200);
        assertTrue(v.path("enabled").asBoolean()); assertEquals(1,v.path("revision").asLong());
        var listing=call(get(base+"/0/versions"),"alice",null,200);
        assertEquals(1,listing.path("total").asInt()); assertEquals("HW-A",listing.path("items").get(0).path("hardwareVersions").get(0).asText());
        assertEquals("",call(get(A+"/products/"+product),"alice",null,200).path("configuration").path("firmwareVersion").asText());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM device_management_audit WHERE action LIKE 'FIRMWARE_%'",Integer.class));
    }
    @Test void channelRulesAndUniquenessAreEnforced() throws Exception {
        channel(0,"wrong","TUYA",400); channel(2,"radio","TUYA",400); channel(256,"radio","TUYA",400);
        channel(0,"main","TUYA",201); channel(0,"main","TUYA",409); channel(10,"main","TUYA",400);
        channel(10,"radio","OPAQUE",201); channel(11,"radio","TUYA",409); channel(12,"custom","SEMVER",400);
        assertEquals(2,call(get(base),"alice",null,200).size());
    }
    @Test void versionOrderingUsesNumericSegmentsAndOpaqueVersionsHaveNoInventedOrder() throws Exception {
        assertTrue(FirmwareVersionRule.TUYA.compare("1.10.0","1.9.0").orElseThrow()>0);
        assertTrue(FirmwareVersionRule.OPAQUE.compare("A7_R12","20261021").isEmpty());
        assertEquals(0,FirmwareVersionRule.OPAQUE.compare("same","same").orElseThrow());
        channel(0,"main","TUYA",201);
        for (String target:List.of("1.9.0","1.0.0","1.10","100.0.0","01.10.0","1.10.0-beta")) {
            var input=version(); input.put("version",target);call(post(base+"/0/versions"),"alice",input,400);
        }
        createVersion(); channel(10,"radio","OPAQUE",201);
        var input=version(); input.put("version","A7_R12");input.put("sourceVersions",List.of("build-2026"));
        call(post(base+"/10/versions"),"alice",input,201);
        input.put("sourceVersions",List.of("A7_R12"));call(post(base+"/10/versions"),"alice",input,400);
    }
    @Test void compatibilityMustBeExplicitAndCannotContainDuplicatesOrNulls() throws Exception {
        channel(0,"main","TUYA",201);
        for (String field:List.of("sourceVersions","hardwareVersions")) {
            for (Object value:Arrays.asList(null,List.of(),Arrays.asList((String)null),List.of("HW-A","HW-A"),List.of(" HW-A"))) {
                var input=version();input.put(field,value);call(post(base+"/0/versions"),"alice",input,400);
            }
        }
        var input=version();input.put("sourceVersions",List.of("9.0.0"));call(post(base+"/0/versions"),"alice",input,400);
        input.put("sourceVersions",Collections.nCopies(101,"1.0.0"));call(post(base+"/0/versions"),"alice",input,400);
    }
    @Test void packageValidationReusesTheDeviceProtocolContract() throws Exception {
        channel(0,"main","TUYA",201);
        for (var invalid:List.of(Map.entry("url",(Object)"http://example.com/a.bin"),Map.entry("url",(Object)"https://user:secret@example.com/a.bin"),Map.entry("url",(Object)"https://example.com/a.bin#part"),Map.entry("size",(Object)0),Map.entry("sha256",(Object)"bad"),Map.entry("keyId",(Object)""),Map.entry("signature",(Object)"invalid"))) {
            var input=version();var a=artifact();a.put(invalid.getKey(),invalid.getValue());input.put("artifact",a);call(post(base+"/0/versions"),"alice",input,400);
        }
        var input=version();input.put("artifact",null);call(post(base+"/0/versions"),"alice",input,400);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_version",Integer.class));
    }
    @Test void permissionsScopeCsrfAndPaginationAreEnforced() throws Exception {
        channel(0,"main","TUYA",201);var v=createVersion();
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        call(get(base),"reader",null,200);
        call(post(base+"/0/versions"),"reader",version(),403);call(post(base+"/0/versions"),"viewer",version(),403);
        mvc.perform(post(base).with(user("alice")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        call(get(B+"/products/"+product+"/firmware/channels"),"bob",null,404);
        call(get(PLATFORM+"/products/"+product+"/firmware/channels"),"alice",null,403);
        call(get(PLATFORM+"/products/"+product+"/firmware/channels"),"root",null,200);
        call(get(base+"/0/versions").param("page","0"),"alice",null,400);
        call(get(base+"/0/versions").param("size","101"),"alice",null,400);
        call(get(base+"/9/versions"),"alice",null,404);
        call(put(base+"/9/versions/"+v.path("id").asText()+"/status"),"alice",Map.of("enabled",true,"revision",0),404);
        jdbc.update("DELETE FROM identity_role_permission WHERE permission='product:publish'");
        call(put(base+"/0/versions/"+v.path("id").asText()+"/status"),"alice",Map.of("enabled",true,"revision",0),403);
        jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='ta'");call(get(base),"alice",null,403);
    }
    @Test void staleRevisionsConflictAndDisabledProductsStillAllowDisablingVersions() throws Exception {
        channel(0,"main","TUYA",201);var v=createVersion();String path=base+"/0/versions/"+v.path("id").asText()+"/status";
        call(put(path),"alice",Map.of("enabled",true,"revision",0),200);
        call(put(path),"alice",Map.of("enabled",false,"revision",0),409);
        call(put(A+"/products/"+product+"/status"),"alice",Map.of("enabled",false,"revision",0),200);
        call(put(path),"alice",Map.of("enabled",false,"revision",1),200);
        call(put(path),"alice",Map.of("enabled",true,"revision",2),409);
        channel(9,"mcu","TUYA",409);call(post(base+"/0/versions"),"alice",version(),409);
        call(delete(A+"/products/"+product).param("revision","1"),"alice",null,409);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_version",Integer.class));
    }
    @Test void productLockSerializesConcurrentRegistrationsAndStatusChanges() throws Exception {
        channel(0,"main","TUYA",201);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt=()->{start.await();return mvc.perform(body(post(base+"/0/versions"),"alice",version())).andReturn().getResponse().getStatus();};
            var first=pool.submit(attempt);var second=pool.submit(attempt);start.countDown();
            assertEquals(Set.of(201,409),Set.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));
            String id=call(get(base+"/0/versions"),"alice",null,200).path("items").get(0).path("id").asText();
            var gate=new CountDownLatch(1);
            Callable<Integer> toggle=()->{gate.await();return mvc.perform(body(put(base+"/0/versions/"+id+"/status"),"alice",Map.of("enabled",true,"revision",0))).andReturn().getResponse().getStatus();};
            first=pool.submit(toggle);second=pool.submit(toggle);gate.countDown();
            assertEquals(Set.of(200,409),Set.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));
        } finally { pool.shutdownNow(); }
    }
    @Test void channelCountIsBoundedByTheProtocolInventoryLimit() throws Exception {
        for (int i=10;i<26;i++) channel(i,"component_"+i,"TUYA",201);
        channel(26,"extra","TUYA",409);
    }
    String sign(byte[] bytes) throws Exception { var signature=Signature.getInstance("Ed25519");signature.initSign(SIGNING.getPrivate());signature.update(bytes);return Base64.getEncoder().encodeToString(signature.sign()); }
    MockHttpServletRequestBuilder upload(String path,byte[] bytes,String key,String signature,String actor) {
        return multipart(path).file(new MockMultipartFile("file","../../ignored.bin","application/octet-stream",bytes)).param("keyId",key).param("signature",signature).with(user(actor)).with(csrf());
    }
    JsonNode uploaded(byte[] bytes) throws Exception {
        return json.readTree(mvc.perform(upload(base+"/0/files",bytes,"factory-key",sign(bytes),"alice")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    @Test void uploadsActualBytesVerifiesSignatureAndResolvesFileIdsWithoutTrustingClientMetadata() throws Exception {
        channel(0,"main","TUYA",201);byte[] bytes={0,1,2,3,4,5};var file=uploaded(bytes);
        assertEquals(bytes.length,file.path("size").asLong());assertTrue(file.path("signatureVerified").asBoolean());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),file.path("sha256").asText());
        var input=version();input.put("artifact",Map.of("fileId",file.path("fileId").asText(),"size",999999,"sha256","forged"));
        var v=call(post(base+"/0/versions"),"alice",input,201);assertEquals(bytes.length,v.path("artifact").path("size").asLong());
        assertTrue(v.path("artifact").path("url").isNull());assertEquals(file.path("sha256"),v.path("artifact").path("sha256"));
        String download=base+"/0/versions/"+v.path("id").asText()+"/download";
        var result=mvc.perform(get(download).with(user("alice"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        assertTrue(json.readTree(result.getContentAsString()).path("url").asText().startsWith("https://files.example.com/firmware/ta/"+product+"/0/"));
        assertFalse(jdbc.queryForObject("SELECT storage_reference FROM device_firmware_file",String.class).contains("signature=temporary"));
        storage.http=true;call(get(download),"alice",null,409);
    }
    @Test void rejectsUntrustedSignaturesTamperedOrOversizedContentBeforeWritingS3() throws Exception {
        channel(0,"main","TUYA",201);byte[] bytes={1,2,3};String valid=sign(bytes);
        mvc.perform(upload(base+"/0/files",bytes,"unknown",valid,"alice")).andExpect(status().isBadRequest());
        mvc.perform(upload(base+"/0/files",new byte[]{3,2,1},"factory-key",valid,"alice")).andExpect(status().isBadRequest());
        mvc.perform(upload(base+"/0/files",bytes,"factory-key","invalid","alice")).andExpect(status().isBadRequest());
        mvc.perform(upload(base+"/0/files",new byte[65],"factory-key",sign(new byte[65]),"alice")).andExpect(status().isPayloadTooLarge());
        mvc.perform(upload(base+"/0/files",new byte[0],"factory-key",sign(new byte[0]),"alice")).andExpect(status().isBadRequest());
        assertEquals(0,storage.puts);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_file",Integer.class));
    }
    @Test void uploadAndDownloadRespectScopesPermissionsCsrfAndChannelOwnership() throws Exception {
        channel(0,"main","TUYA",201);byte[] bytes={1,2};String signature=sign(bytes);
        mvc.perform(upload(base+"/0/files",bytes,"factory-key",signature,"reader")).andExpect(status().isForbidden());
        mvc.perform(upload(base+"/0/files",bytes,"factory-key",signature,"viewer")).andExpect(status().isForbidden());
        mvc.perform(multipart(base+"/0/files").file(new MockMultipartFile("file",bytes)).param("keyId","factory-key").param("signature",signature).with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(upload(B+"/products/"+product+"/firmware/channels/0/files",bytes,"factory-key",signature,"bob")).andExpect(status().isNotFound());
        var file=uploaded(bytes);channel(9,"mcu","TUYA",201);
        var input=version();input.put("artifact",Map.of("fileId",file.path("fileId").asText()));call(post(base+"/9/versions"),"alice",input,404);
        var v=call(post(base+"/0/versions"),"alice",input,201);
        call(get(B+"/products/"+product+"/firmware/channels/0/versions/"+v.path("id").asText()+"/download"),"bob",null,404);
    }
    @Test void failedStorageDoesNotPersistFilesAndRevokedProductEligibilityCompensatesUpload() throws Exception {
        channel(0,"main","TUYA",201);byte[] bytes={1};String signature=sign(bytes);storage.fail=true;
        mvc.perform(upload(base+"/0/files",bytes,"factory-key",signature,"alice")).andExpect(status().isBadGateway());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_file",Integer.class));
        storage.fail=false;storage.onPut=()->jdbc.update("UPDATE device_product SET enabled=FALSE WHERE id=?",product);
        mvc.perform(upload(base+"/0/files",bytes,"factory-key",signature,"alice")).andExpect(status().isConflict());
        assertTrue(storage.objects.isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_file",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_management_audit WHERE action='FIRMWARE_FILE_UPLOADED'",Integer.class));
    }
}

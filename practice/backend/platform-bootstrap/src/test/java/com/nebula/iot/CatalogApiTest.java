package com.nebula.iot;

import com.fasterxml.jackson.databind.*;
import java.util.*;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test"})
@ActiveProfiles("management") @AutoConfigureMockMvc
class CatalogApiTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
 @Autowired com.nebula.iot.network.mqtt.VertxMqttGateway mqtt;
 @Autowired com.nebula.iot.device.api.DeviceConnectionDirectory connectionDirectory;
 @Autowired com.nebula.iot.device.application.RegisteredDeviceDirectory registeredDirectory;
 private static class TestMqttClient extends org.eclipse.paho.mqttv5.client.MqttClient implements AutoCloseable {
  TestMqttClient(String uri,String id)throws org.eclipse.paho.mqttv5.common.MqttException{super(uri,id,new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());}
  @Override public void close()throws org.eclipse.paho.mqttv5.common.MqttException{if(isConnected())disconnectForcibly(0,0,false);super.close(true);}
 }
 private TestMqttClient connectDevice(String clientId,String username,String password)throws Exception{
  var client=new TestMqttClient("tcp://127.0.0.1:"+mqtt.actualPort(),clientId);
  client.setTimeToWait(5000);
  var options=new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();
  options.setUserName(username);options.setPassword(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));options.setCleanStart(true);options.setSessionExpiryInterval(0L);options.setConnectionTimeout(3);
  try{client.connect(options);return client;}catch(Exception error){client.close(true);throw error;}
 }
 private void rejectsDevice(String clientId,String username,String password){
  assertThrows(org.eclipse.paho.mqttv5.common.MqttException.class,()->{try(var unexpected=connectDevice(clientId,username,password)){unexpected.disconnect();}});
 }
 private JsonNode activateDevice(String scope,JsonNode credential)throws Exception{
  return json.readTree(mvc.perform(body(as(put(scope+"/devices/"+credential.path("clientId").asText()),"root"),Map.of("name","MQTT test","lifecycle","ACTIVE","revision",credential.path("device").path("revision").asLong()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
 }
 @Test void registeredCredentialsAuthenticateOnlyActiveDevicesAndBindClientIdentity()throws Exception{
  assertTrue(mqtt.isRunning());
  var product=publish(A,"alice",create(A,"alice","mqtt"),"v1");
  var credential=register(A,"alice",product,"mqtt");
  String id=credential.path("clientId").asText(),secret=credential.path("secret").asText();
  assertEquals("ACTIVE",credential.path("device").path("lifecycle").asText());
  rejectsDevice(id,id,"wrong");rejectsDevice("forged-client",id,secret);rejectsDevice(id,"unknown",secret);
  try(var client=connectDevice(id,id,secret)){
   assertTrue(client.isConnected());
   var key=new com.nebula.iot.core.device.DeviceKey(new com.nebula.iot.core.identity.TenantId("ta"),new com.nebula.iot.core.identity.DeviceId(id));
   assertEquals("v1",connectionDirectory.resolve(key).orElseThrow().model().version());
   var forged=new com.nebula.iot.core.device.DeviceKey(new com.nebula.iot.core.identity.TenantId("tb"),key.deviceId());
   assertTrue(connectionDirectory.resolve(forged).isEmpty());
   client.disconnect();
  }
 }
 @Test void rotationDisconnectsExistingSessionAndRejectsOldSecret()throws Exception{
  var credential=register(PLATFORM,"root",publish(PLATFORM,"root",create(PLATFORM,"root","rotate-mqtt"),"v1"),"mqtt");
  String id=credential.path("clientId").asText(),secret=credential.path("secret").asText();
  var active=activateDevice(PLATFORM,credential);
  try(var client=connectDevice(id,id,secret)){
   var rotated=json.readTree(mvc.perform(body(as(post(PLATFORM+"/devices/"+id+"/credential"),"root"),Map.of("revision",active.path("revision").asLong()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
   while(client.isConnected()&&System.nanoTime()<deadline)Thread.sleep(10);
   assertFalse(client.isConnected());rejectsDevice(id,id,secret);
   try(var replacement=connectDevice(id,id,rotated.path("secret").asText())){assertTrue(replacement.isConnected());replacement.disconnect();}
  }
 }
 @Test void disabledProductTenantAndDeviceCannotAuthenticate()throws Exception{
  var product=publish(A,"alice",create(A,"alice","eligibility"),"v1");
  var credential=register(A,"alice",product,"mqtt");activateDevice(A,credential);
  String id=credential.path("clientId").asText(),secret=credential.path("secret").asText();
  jdbc.update("UPDATE device_product SET enabled=FALSE WHERE id=?",product.path("id").asText());rejectsDevice(id,id,secret);
  jdbc.update("UPDATE device_product SET enabled=TRUE WHERE id=?",product.path("id").asText());
  // Direct SQL fixture changes bypass the service's after-commit cache invalidation.
  registeredDirectory.invalidate(id);
  jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='ta'");rejectsDevice(id,id,secret);
  jdbc.update("UPDATE tenant SET enabled=TRUE WHERE id='ta'");
  registeredDirectory.invalidate(id);
  var active=jdbc.queryForObject("SELECT revision FROM device_registration WHERE id=?",Long.class,id);
  try(var client=connectDevice(id,id,secret)){
   mvc.perform(body(as(put(A+"/devices/"+id),"root"),Map.of("name","MQTT test","lifecycle","DISABLED","revision",active))).andExpect(status().isOk());
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
   while(client.isConnected()&&System.nanoTime()<deadline)Thread.sleep(10);
   assertFalse(client.isConnected());
  }
  rejectsDevice(id,id,secret);
 }
 static final String PLATFORM="/api/management/catalog/platform",A="/api/management/catalog/tenants/ta",B="/api/management/catalog/tenants/tb";
 @BeforeEach void seed(){
  for(String table:List.of("device_product_directory","device_management_audit","device_registration","device_model_version","device_product","identity_account_role","identity_role_permission","identity_role","tenant_membership","identity_account","tenant"))jdbc.update("DELETE FROM "+table);
  jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','A'),('tb','B')");
  jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('root','root','unused',TRUE,NULL),('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'tb'),('v','viewer','unused',FALSE,'ta'),('o','outsider','unused',FALSE,'tb')");
  jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','a','OWNER'),('tb','b','ADMIN'),('ta','v','VIEWER')");
  jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('business','business','Business')");
  for(String p:List.of("product:read","product:write","product:publish","device:read","device:write","device:credential"))jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('business',?)",p);
  for(String id:List.of("a","b","v","o"))jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES (?,'business')",id);
 }
 MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r,String actor){return r.with(user(actor)).with(csrf());}
 MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r,Object value)throws Exception{return r.contentType("application/json").content(json.writeValueAsString(value));}
 JsonNode create(String scope,String actor,String code)throws Exception{return json.readTree(mvc.perform(body(as(post(scope+"/products"),actor),Map.of("code",code,"name","Warmer "+code,"description","test"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
 JsonNode publish(String scope,String actor,JsonNode p,String version)throws Exception{return json.readTree(mvc.perform(body(as(post(scope+"/products/"+p.get("id").asText()+"/publish"),actor),Map.of("confirmEmpty",true,"revision",p.get("revision").asLong()))).andExpect(status().isOk()).andExpect(jsonPath("$.latestVersion").value(version)).andReturn().getResponse().getContentAsString());}
 JsonNode register(String scope,String actor,JsonNode p,String code)throws Exception{return json.readTree(mvc.perform(body(as(post(scope+"/devices"),actor),Map.of("productId",p.get("id").asText(),"code",code,"name","Warmer "+code,"modelVersion","v1"))).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());}
 @Test void shadowWithoutRedisReturnsUnavailableInsteadOfAnEmptySuccess()throws Exception{
  var product=publish(A,"alice",create(A,"alice","shadow-disabled"),"v1");
  var device=register(A,"alice",product,"shadow-disabled").path("device").path("id").asText();
  mvc.perform(as(get(A+"/devices/"+device+"/shadow"),"alice")).andExpect(status().isServiceUnavailable());
 }
 @Test void deleteRequiresDisabledEmptyProductAndChecksScopeRevisionAndPermissions()throws Exception{
  for(String scope:List.of(A,PLATFORM)){
   String actor=scope.equals(A)?"alice":"root";
   JsonNode p=publish(scope,actor,create(scope,actor,"deletable"),"v1");String id=p.path("id").asText(),path=scope+"/products/"+id;
   long revision=p.path("revision").asLong();
   mvc.perform(as(delete(path).param("revision",String.valueOf(revision)),actor)).andExpect(status().isConflict());
   mvc.perform(body(as(put(path+"/status"),actor),Map.of("enabled",false,"revision",revision))).andExpect(status().isOk());
   mvc.perform(as(delete(path).param("revision",String.valueOf(revision)),actor)).andExpect(status().isConflict());
   mvc.perform(delete(path).param("revision",String.valueOf(revision+1)).with(user(actor))).andExpect(status().isForbidden());
   mvc.perform(as(delete(path).param("revision","-1"),actor)).andExpect(status().isBadRequest());
   if(scope.equals(A)){
    mvc.perform(as(delete(path).param("revision",String.valueOf(revision+1)),"viewer")).andExpect(status().isForbidden());
    mvc.perform(as(delete(B+"/products/"+id).param("revision",String.valueOf(revision+1)),"bob")).andExpect(status().isNotFound());
    jdbc.update("DELETE FROM identity_role_permission WHERE role_id='business' AND permission='product:write'");
    mvc.perform(as(delete(path).param("revision",String.valueOf(revision+1)),actor)).andExpect(status().isForbidden());
    jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('business','product:write')");
   }
   mvc.perform(as(delete(path).param("revision",String.valueOf(revision+1)),actor)).andExpect(status().isNoContent());
   mvc.perform(as(get(path),actor)).andExpect(status().isNotFound());
   assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_model_version WHERE product_id=?",Integer.class,id));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_management_audit WHERE resource_id=? AND action='PRODUCT_DELETED'",Integer.class,id));
  }
 }
 @Test void productsWithDevicesCannotBeDeletedEvenAfterRetirement()throws Exception{
  JsonNode p=publish(A,"alice",create(A,"alice","occupied"),"v1");String id=p.path("id").asText(),path=A+"/products/"+id;
  JsonNode device=register(A,"alice",p,"one").path("device");
  mvc.perform(body(as(put(path+"/status"),"alice"),Map.of("enabled",false,"revision",p.path("revision").asLong()))).andExpect(status().isOk());
  for(String lifecycle:List.of("ACTIVE","RETIRED")){
   if(lifecycle.equals("RETIRED"))mvc.perform(body(as(put(A+"/devices/"+device.path("id").asText()),"alice"),Map.of("name","One","lifecycle",lifecycle,"revision",0))).andExpect(status().isOk());
   mvc.perform(as(delete(path).param("revision",String.valueOf(p.path("revision").asLong()+1)),"alice")).andExpect(status().isConflict());
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_model_version WHERE product_id=?",Integer.class,id));
  }
 }
 @Test void productConfigurationIsPersistedValidatedAndRevisionProtected()throws Exception{
  JsonNode p=create(A,"alice","configured");String id=p.get("id").asText();
  assertEquals("MQTT",p.path("configuration").path("transportProtocol").asText());
  var settings=Map.of("category","feed-warm","manufacturer","Nebula","deviceType","DIRECT","networkWay","WIFI","supportUrl","https://example.com/manual");
  var payload=new HashMap<String,Object>();payload.put("name","Configured");payload.put("description","");payload.put("model",Map.of("properties",Map.of(),"events",Map.of(),"operations",Map.of()));payload.put("revision",0);payload.put("configuration",settings);
  mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"alice"),payload)).andExpect(status().isOk()).andExpect(jsonPath("$.configuration.category").value("feed-warm")).andExpect(jsonPath("$.revision").value(1));
  mvc.perform(as(get(A+"/products/"+id),"alice")).andExpect(jsonPath("$.configuration.networkWay").value("WIFI"));
  mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"alice"),payload)).andExpect(status().isConflict());
  payload.put("revision",1);payload.remove("configuration");
  mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"alice"),payload)).andExpect(status().isOk()).andExpect(jsonPath("$.configuration.manufacturer").value("Nebula"));
  payload.put("revision",2);payload.put("configuration",Map.of("transportProtocol","UNKNOWN"));
  mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"alice"),payload)).andExpect(status().isBadRequest());
  payload.put("configuration",Map.of("supportUrl","javascript:alert(1)"));
  mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"alice"),payload)).andExpect(status().isBadRequest());
  payload.put("configuration",settings);
  mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"viewer"),payload)).andExpect(status().isForbidden());
  mvc.perform(body(as(put(B+"/products/"+id+"/draft"),"bob"),payload)).andExpect(status().isNotFound());
 }
 @Test void productDirectoryFiltersBeforePaginationAndKeepsScope()throws Exception{
  for(var entry:Map.of("warmer","feed-warm","mixer","feed-mix","washer","clen-wash").entrySet()){
   mvc.perform(body(as(post(A+"/products"),"alice"),Map.of("code",entry.getKey(),"name",entry.getKey(),"description","","configuration",Map.of("category",entry.getValue())))).andExpect(status().isCreated());
  }
  mvc.perform(body(as(post(B+"/products"),"bob"),Map.of("code","other","name","Other","description","","configuration",Map.of("category","feed-warm")))).andExpect(status().isCreated());
  mvc.perform(as(get(A+"/products").param("category","feed").param("size","1"),"alice")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(1));
  mvc.perform(as(get(A+"/products").param("category","feed-warm"),"alice")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].code").value("warmer"));
  mvc.perform(as(get(A+"/products").param("category","feed-warm").param("query","washer"),"alice")).andExpect(jsonPath("$.total").value(0));
  mvc.perform(as(get(A+"/products").param("category","feed-war"),"alice")).andExpect(jsonPath("$.total").value(0));
 }
 @Test void sharedDirectoryIsVisibleAcrossTenantsButOnlyPlatformAdminCanMaintainIt()throws Exception{
  String path="/api/management/catalog/shared/categories";
  var items=List.of(Map.of("id","feed","label","喂养电器","children",List.of(Map.of("id","feed-warm","label","暖奶器","children",List.of()))));
  mvc.perform(get(path)).andExpect(status().isUnauthorized());
  mvc.perform(body(as(put(path),"root"),Map.of("revision",0,"items",items))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
  for(String actor:List.of("root","alice","bob","viewer"))mvc.perform(as(get(path),actor)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].label").value("喂养电器")).andExpect(jsonPath("$.revision").value(1));
  mvc.perform(as(get(A+"/categories"),"alice")).andExpect(jsonPath("$.items.length()").value(1));
  mvc.perform(as(get(B+"/categories"),"bob")).andExpect(jsonPath("$.items.length()").value(1));
  for(String alias:List.of(path,A+"/categories",PLATFORM+"/categories"))mvc.perform(body(as(put(alias),"alice"),Map.of("revision",1,"items",items))).andExpect(status().isForbidden());
  mvc.perform(body(as(put(path),"root"),Map.of("revision",0,"items",items))).andExpect(status().isConflict());
  mvc.perform(put(path).with(user("root")).contentType("application/json").content(json.writeValueAsString(Map.of("revision",1,"items",items)))).andExpect(status().isForbidden());
  // A tenant-owned product prevents changes to the shared category, including for disabled tenants.
  mvc.perform(body(as(post(B+"/products"),"bob"),Map.of("code","assigned","name","Warmer","description","","configuration",Map.of("category","feed-warm")))).andExpect(status().isCreated());
  jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='tb'");
  mvc.perform(body(as(put(path),"root"),Map.of("revision",1,"items",List.of()))).andExpect(status().isConflict());
  var invalid=List.of(Map.of("id","dupe","label","重复","children",List.of()),Map.of("id","dupe","label","重复","children",List.of()));
  mvc.perform(body(as(put(path),"root"),Map.of("revision",1,"items",invalid))).andExpect(status().isBadRequest());
  jdbc.update("DELETE FROM identity_account_role WHERE account_id='a'");
  mvc.perform(as(get(path),"alice")).andExpect(status().isForbidden());
 }
 @Test void categoryNamesCanChangeWithoutBreakingProductAssociation()throws Exception{
  String path="/api/management/catalog/shared/categories";
  var items=List.of(Map.of("id","feed","label","喂养电器","children",List.of(Map.of("id","feed-warm","label","暖奶器","children",List.of()))));
  mvc.perform(body(as(put(path),"root"),Map.of("revision",0,"items",items))).andExpect(status().isOk());
  mvc.perform(body(as(post(A+"/products"),"alice"),Map.of("code","linked","name","Linked","description","","configuration",Map.of("category","feed-warm")))).andExpect(status().isCreated());
  var renamed=List.of(Map.of("id","feed","label","喂养设备","children",List.of(Map.of("id","feed-warm","label","智能暖奶器","children",List.of()))));
  mvc.perform(body(as(put(path),"root"),Map.of("revision",1,"items",renamed))).andExpect(status().isOk());
  mvc.perform(as(get(A+"/products").param("category","feed"),"alice")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].configuration.category").value("feed-warm"));
  var wrongParent=List.of(Map.of("id","feed","label","喂养","children",List.of(Map.of("id","care-warm","label","错误","children",List.of()))));
  mvc.perform(body(as(put(path),"root"),Map.of("revision",2,"items",wrongParent))).andExpect(status().isBadRequest());
 }
 @Test void migrationAssignsEnglishIdsAndConvertsExistingProductReferences()throws Exception{
  var items=List.of(Map.of("label","喂养电器","children",List.of(Map.of("label","暖奶器","children",List.of()))));
  jdbc.update("INSERT INTO device_product_directory(scope_key,tree_json,revision) VALUES ('platform',?,4)",json.writeValueAsString(Map.of("revision",4,"items",items)));
  var product=create(A,"alice","legacy");String id=product.path("id").asText();
  jdbc.update("UPDATE device_product SET configuration=? WHERE id=?",json.writeValueAsString(Map.of("category","喂养电器 / 暖奶器")),id);
  var context=org.mockito.Mockito.mock(org.flywaydb.core.api.migration.Context.class);
  try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
   org.mockito.Mockito.when(context.getConnection()).thenReturn(connection);
   new db.migration.device.V14__category_identifiers().migrate(context);
  }
  mvc.perform(as(get(PLATFORM+"/categories"),"root")).andExpect(jsonPath("$.items[0].id").value("feed")).andExpect(jsonPath("$.items[0].children[0].id").value("feed-warm")).andExpect(jsonPath("$.revision").value(5));
  mvc.perform(as(get(A+"/products").param("category","feed-warm"),"alice")).andExpect(jsonPath("$.total").value(1));
 }
 @Test void platformAdminSeesAllTenantsAndOrphanDataWithoutMembership()throws Exception{
  var pa=publish(A,"alice",create(A,"alice","same"),"v1");
  var pb=publish(B,"bob",create(B,"bob","same"),"v1");
  var pp=publish(PLATFORM,"root",create(PLATFORM,"root","same"),"v1");
  var da=register(A,"alice",pa,"same").path("device");
  register(B,"bob",pb,"same");register(PLATFORM,"root",pp,"same");
  jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='tb'");
  mvc.perform(as(get(PLATFORM+"/products").param("size","2"),"root")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items.length()").value(2));
  mvc.perform(as(get(PLATFORM+"/devices"),"root")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
  mvc.perform(as(get(PLATFORM+"/products/"+pb.path("id").asText()),"root")).andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value("tb"));
  mvc.perform(as(get(PLATFORM+"/products/"+pa.path("id").asText()+"/models"),"root")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
  mvc.perform(as(get(PLATFORM+"/devices/"+da.path("id").asText()),"root")).andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value("ta"));
  mvc.perform(as(get(PLATFORM+"/devices").param("productId",pa.path("id").asText()),"root")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
  mvc.perform(as(get(A+"/products"),"alice")).andExpect(jsonPath("$.total").value(1));
  mvc.perform(as(get(PLATFORM+"/devices"),"alice")).andExpect(status().isForbidden());
 }
 @Test void platformResourcesAreNullTenantAndSuperAdminOnly()throws Exception{
  JsonNode p=create(PLATFORM,"root","same-code");assertTrue(p.get("tenantId").isNull());
  mvc.perform(as(get(PLATFORM+"/products"),"alice")).andExpect(status().isForbidden());
  mvc.perform(as(get(PLATFORM+"/products/"+p.get("id").asText()),"alice")).andExpect(status().isForbidden());
  mvc.perform(as(get(A+"/products/"+p.get("id").asText()),"alice")).andExpect(status().isNotFound());
  create(A,"alice","same-code");
 }
 @Test void tenantsAreIsolatedAndForgedBodyCannotChangeScope()throws Exception{
  JsonNode p=create(A,"alice","warmer");create(B,"bob","warmer");
  mvc.perform(as(get(A+"/products"),"alice")).andExpect(jsonPath("$.total").value(1));
  mvc.perform(as(get(A+"/products"),"bob")).andExpect(status().isForbidden());
  mvc.perform(as(get(B+"/products/"+p.get("id").asText()),"bob")).andExpect(status().isNotFound());
  mvc.perform(body(as(post(A+"/products"),"alice"),Map.of("tenantId","tb","code","forced","name","Forced","description",""))).andExpect(status().isCreated()).andExpect(jsonPath("$.tenantId").value("ta"));
 }
 @Test void membershipViewerAndPermissionChecksAreIndependent()throws Exception{
  mvc.perform(as(get(A+"/products"),"root")).andExpect(status().isOk());
  mvc.perform(as(get(A+"/products"),"outsider")).andExpect(status().isForbidden());
  mvc.perform(as(get(A+"/products"),"viewer")).andExpect(status().isOk());
  mvc.perform(body(as(post(A+"/products"),"viewer"),Map.of("code","no","name","No","description",""))).andExpect(status().isForbidden());
  jdbc.update("DELETE FROM identity_account_role WHERE account_id='a'");
  mvc.perform(as(get(A+"/products"),"alice")).andExpect(status().isForbidden());
 }
 @Test void draftCanChangeButPublishedModelAndDeviceBindingDoNot()throws Exception{
  JsonNode p=create(A,"alice","warmer");String id=p.get("id").asText();
  Map<String,Object> spec=Map.of("properties",Map.of("power",Map.of("id","power","schema",Map.of("type","BOOLEAN"),"access","READ_WRITE")),"events",Map.of(),"operations",Map.of());
  p=json.readTree(mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"alice"),Map.of("name","Warmer","description","v1 model","model",spec,"revision",0))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  p=publish(A,"alice",p,"v1");JsonNode d=register(A,"alice",p,"warmer-1");
  p=json.readTree(mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"alice"),Map.of("name","Warmer","description","v2 model","model",Map.of("properties",Map.of(),"events",Map.of(),"operations",Map.of()),"revision",p.get("revision").asLong()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  publish(A,"alice",p,"v2");
  mvc.perform(as(get(A+"/devices/"+d.get("device").get("id").asText()),"alice")).andExpect(jsonPath("$.modelVersion").value("v1"));
  mvc.perform(as(get(A+"/products/"+id+"/models"),"alice")).andExpect(jsonPath("$.length()").value(2));
  String stored=jdbc.queryForObject("SELECT model_json FROM device_model_version WHERE product_id=? AND version='v1'",String.class,id);assertTrue(json.readTree(stored).get("properties").has("power"));
 }
 @Test void duplicateVersionsCodesAndStaleWritesFail()throws Exception{
  JsonNode p=publish(A,"alice",create(A,"alice","warmer"),"v1");String id=p.get("id").asText();
  mvc.perform(body(as(post(A+"/products/"+id+"/publish"),"alice"),Map.of("version","v1","revision",p.get("revision").asLong()))).andExpect(status().isConflict());
  mvc.perform(body(as(post(A+"/products"),"alice"),Map.of("code","warmer","name","Dup","description",""))).andExpect(status().isConflict());
  mvc.perform(body(as(put(A+"/products/"+id+"/status"),"alice"),Map.of("enabled",false,"revision",0))).andExpect(status().isConflict());
 }
 @Test void autoVersionsRequireEmptyConfirmationSkipLegacyLabelsAndRejectUnchangedDraft()throws Exception{
  JsonNode p=create(A,"alice","automatic");String id=p.path("id").asText(),path=A+"/products/"+id;
  mvc.perform(body(as(post(path+"/publish"),"alice"),Map.of("revision",0))).andExpect(status().isConflict());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_model_version WHERE product_id=?",Integer.class,id));
  // Historical labels are preserved; only numeric vN labels participate in allocation.
  String model=json.writeValueAsString(p.path("draft"));
  jdbc.update("INSERT INTO device_model_version(product_id,version,model_json) VALUES (?,'release-beta',?),(?,'v9',?),(?,'v2',?)",id,model,id,model,id,model);
  p=json.readTree(mvc.perform(body(as(post(path+"/publish"),"alice"),Map.of("revision",0,"confirmEmpty",true,"version","v999"))).andExpect(status().isOk()).andExpect(jsonPath("$.latestVersion").value("v10")).andReturn().getResponse().getContentAsString());
  mvc.perform(body(as(post(A+"/devices"),"alice"),Map.of("productId",id,"code","bound","name","Bound","modelVersion","v10"))).andExpect(status().isCreated());
  mvc.perform(as(get(path+"/models"),"alice")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.version == 'v10')].deviceCount").value(org.hamcrest.Matchers.contains(1)));
  mvc.perform(body(as(post(path+"/publish"),"alice"),Map.of("revision",p.path("revision").asLong(),"confirmEmpty",true))).andExpect(status().isConflict());
  assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM device_model_version WHERE product_id=?",Integer.class,id));
 }
 @Test void concurrentPublicationAllocatesOnlyOneVersionAndRejectsTheStaleRequest()throws Exception{
  var p=create(A,"alice","concurrent");String id=p.path("id").asText(),path=A+"/products/"+id+"/publish";
  var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
  var start=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.Callable<Integer> request=()->{start.await();return mvc.perform(body(as(post(path),"alice"),Map.of("revision",0,"confirmEmpty",true))).andReturn().getResponse().getStatus();};
  try{
   var first=executor.submit(request);var second=executor.submit(request);start.countDown();
   assertEquals(Set.of(200,409),Set.of(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS)));
   assertEquals("v1",jdbc.queryForObject("SELECT latest_version FROM device_product WHERE id=?",String.class,id));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_model_version WHERE product_id=?",Integer.class,id));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_management_audit WHERE resource_id=? AND action='MODEL_PUBLISHED'",Integer.class,id));
  }finally{executor.shutdownNow();}
 }
 @Test void publicationStillRequiresCsrfAndWritableMembership()throws Exception{
  var p=create(A,"alice","protected");String id=p.path("id").asText(),path=A+"/products/"+id+"/publish";
  mvc.perform(body(post(path).with(user("alice")),Map.of("revision",0,"confirmEmpty",true))).andExpect(status().isForbidden());
  mvc.perform(body(as(post(path),"viewer"),Map.of("revision",0,"confirmEmpty",true))).andExpect(status().isForbidden());
  mvc.perform(body(as(post(B+"/products/"+id+"/publish"),"bob"),Map.of("revision",0,"confirmEmpty",true))).andExpect(status().isNotFound());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_model_version WHERE product_id=?",Integer.class,id));
 }
 @Test void deviceCannotReferenceAnotherScopeProduct()throws Exception{
  JsonNode p=publish(B,"bob",create(B,"bob","warmer"),"v1");
  mvc.perform(body(as(post(A+"/devices"),"alice"),Map.of("productId",p.get("id").asText(),"code","cross","name","Cross","modelVersion","v1"))).andExpect(status().isNotFound());
  mvc.perform(body(as(post(PLATFORM+"/devices"),"root"),Map.of("productId",p.get("id").asText(),"code","cross","name","Cross","modelVersion","v1"))).andExpect(status().isNotFound());
 }
 @Test void credentialsRemainReadableAndRotateReplacesTheStoredValue()throws Exception{
  JsonNode p=publish(A,"alice",create(A,"alice","warmer"),"v1");JsonNode c=register(A,"alice",p,"warmer-1");String id=c.get("device").get("id").asText(),secret=c.get("secret").asText();
  String oldHash=jdbc.queryForObject("SELECT credential_hash FROM device_registration WHERE id=?",String.class,id);assertTrue(encoder.matches(secret,oldHash));
  assertEquals(secret,jdbc.queryForObject("SELECT credential_secret FROM device_registration WHERE id=?",String.class,id));
  mvc.perform(as(get(A+"/devices/"+id+"/credential"),"alice")).andExpect(jsonPath("$.secret").value(secret));
  JsonNode rotated=json.readTree(mvc.perform(body(as(post(A+"/devices/"+id+"/credential"),"alice"),Map.of("revision",0))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  assertNotEquals(secret,rotated.get("secret").asText());assertEquals(2,rotated.get("device").get("credentialVersion").asInt());
  String hash=jdbc.queryForObject("SELECT credential_hash FROM device_registration WHERE id=?",String.class,id);assertFalse(encoder.matches(secret,hash));
  assertEquals(rotated.get("secret").asText(),jdbc.queryForObject("SELECT credential_secret FROM device_registration WHERE id=?",String.class,id));
  mvc.perform(body(as(post(A+"/devices/"+id+"/credential"),"alice"),Map.of("revision",0))).andExpect(status().isConflict());
 }
 @Test void disabledProductAndUnpublishedVersionRejectRegistration()throws Exception{
  JsonNode p=create(A,"alice","warmer");String id=p.get("id").asText();
  mvc.perform(body(as(post(A+"/devices"),"alice"),Map.of("productId",id,"code","one","name","One","modelVersion","v1"))).andExpect(status().isBadRequest());
  p=publish(A,"alice",p,"v1");
  mvc.perform(body(as(put(A+"/products/"+id+"/status"),"alice"),Map.of("enabled",false,"revision",p.get("revision").asLong()))).andExpect(status().isOk());
  mvc.perform(body(as(post(A+"/devices"),"alice"),Map.of("productId",id,"code","one","name","One","modelVersion","v1"))).andExpect(status().isConflict());
 }
 @Test void lifecycleRetirementIsTerminal()throws Exception{
  JsonNode p=publish(A,"alice",create(A,"alice","warmer"),"v1");JsonNode c=register(A,"alice",p,"one");String id=c.get("device").get("id").asText();
  mvc.perform(body(as(put(A+"/devices/"+id),"alice"),Map.of("name","One","lifecycle","ACTIVE","revision",0))).andExpect(status().isOk());
  mvc.perform(body(as(put(A+"/devices/"+id),"alice"),Map.of("name","One","lifecycle","RETIRED","revision",1))).andExpect(status().isOk());
  mvc.perform(body(as(put(A+"/devices/"+id),"alice"),Map.of("name","One","lifecycle","ACTIVE","revision",2))).andExpect(status().isConflict());
  mvc.perform(body(as(post(A+"/devices/"+id+"/credential"),"alice"),Map.of("revision",2))).andExpect(status().isConflict());
 }
 @Test void invalidModelsAndMissingCsrfFail()throws Exception{
  JsonNode p=create(A,"alice","warmer");String id=p.get("id").asText();
  var invalid=Map.of("properties",Map.of("power",Map.of("id","different","schema",Map.of("type","BOOLEAN"),"access","READ_WRITE")),"events",Map.of(),"operations",Map.of());
  mvc.perform(body(as(put(A+"/products/"+id+"/draft"),"alice"),Map.of("name","Warmer","description","","model",invalid,"revision",0))).andExpect(status().isBadRequest());
  mvc.perform(body(post(A+"/products").with(user("alice")),Map.of("code","unsafe","name","Unsafe","description",""))).andExpect(status().isForbidden());
  mvc.perform(as(get(A+"/products").param("size","101"),"alice")).andExpect(status().isBadRequest());
 }
 @Test void revokedMembershipAndDisabledTenantBlockExistingIdentity()throws Exception{
  create(A,"alice","warmer");jdbc.update("UPDATE tenant_membership SET enabled=FALSE WHERE account_id='a'");
  mvc.perform(as(get(A+"/products"),"alice")).andExpect(status().isForbidden());
  jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='ta'");mvc.perform(as(get(A+"/devices"),"viewer")).andExpect(status().isForbidden());
 }
 @Test void twoDevicesHaveIndependentIdentityAndAuditedChanges()throws Exception{
  JsonNode p=publish(PLATFORM,"root",create(PLATFORM,"root","warmer"),"v1");
  var first=register(PLATFORM,"root",p,"");var second=register(PLATFORM,"root",p,"two");
  assertNotEquals(first.get("clientId").asText(),second.get("clientId").asText());
  assertTrue(first.path("device").path("code").asText().matches("device-[a-hj-km-np-z]{8}"));
  assertTrue(first.get("clientId").asText().matches("[a-hj-km-np-z]{16}"));
  assertEquals(first.get("clientId").asText(),first.get("username").asText());
  assertFalse(first.get("clientId").asText().matches(".*[oil].*"));
  assertNotEquals(first.get("secret").asText(),second.get("secret").asText());
  mvc.perform(as(get(PLATFORM+"/devices"),"root")).andExpect(jsonPath("$.total").value(2));
  assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM device_management_audit",Integer.class));
 }
}

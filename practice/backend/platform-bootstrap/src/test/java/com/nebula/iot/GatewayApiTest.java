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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:gateway;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test","nebula.runtime.worker=false"})
@ActiveProfiles("management") @AutoConfigureMockMvc
class GatewayApiTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
 @Autowired com.nebula.iot.network.mqtt.VertxMqttGateway mqtt;
 @Autowired com.nebula.iot.device.api.DeviceConnectionDirectory connectionDirectory;
 @Autowired com.nebula.iot.bootstrap.subscription.DevicePresence presence;
 @Autowired com.nebula.iot.message.EventBus eventBus;
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
 private org.eclipse.paho.mqttv5.client.MqttConnectionOptions newConnectionOptions(String id,String secret) {
  var options=new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();options.setUserName(id);options.setPassword(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));options.setCleanStart(true);options.setSessionExpiryInterval(0L);options.setConnectionTimeout(3);return options;
 }
 private void rejectsDevice(String clientId,String username,String password){
  assertThrows(org.eclipse.paho.mqttv5.common.MqttException.class,()->{try(var unexpected=connectDevice(clientId,username,password)){unexpected.disconnect();}});
 }
 private JsonNode activateDevice(String scope,JsonNode credential)throws Exception{
  return json.readTree(mvc.perform(body(as(put(scope+"/devices/"+credential.path("clientId").asText()),"root"),Map.of("name","MQTT test","lifecycle","ACTIVE","revision",credential.path("device").path("revision").asLong()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
 }
 static final String PLATFORM="/api/management/catalog/platform",A="/api/management/catalog/tenants/ta",B="/api/management/catalog/tenants/tb";
 @BeforeEach void seed(){
  for(String table:List.of("device_gateway_child","device_product_directory","device_management_audit","device_registration","device_model_version","device_product","identity_account_role","identity_role_permission","identity_role","tenant_membership","identity_account","tenant"))jdbc.update("DELETE FROM "+table);
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
 JsonNode proxyProduct(String scope,String actor,String code,String type)throws Exception {
  var p=json.readTree(mvc.perform(body(as(post(scope+"/products"),actor),Map.of("code",code,"name",code,"description","","configuration",Map.of("deviceType",type)))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
  p=json.readTree(mvc.perform(body(as(put(scope+"/products/"+p.path("id").asText()+"/draft"),actor),Map.of("name",code,"description","","revision",0,"model",Map.of("properties",Map.of("power",Map.of("id","power","schema",Map.of("type","BOOLEAN"),"access","READ_WRITE")),"events",Map.of(),"operations",Map.of())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  return publish(scope,actor,p,"v1");
 }
 @Test void topologyEnforcesTypeScopeRevisionPermissionsAndDeletionProtection()throws Exception {
  var parent=register(A,"alice",proxyProduct(A,"alice","gateway","GATEWAY"),"gateway");
  var cp=proxyProduct(A,"alice","child","CHILD");var child=register(A,"alice",cp,"child");
  var direct=register(A,"alice",publish(A,"alice",create(A,"alice","direct"),"v1"),"direct");
  String pid=parent.path("clientId").asText(),cid=child.path("clientId").asText(),path=A+"/devices/"+cid+"/gateway";
  mvc.perform(body(as(put(path),"viewer"),Map.of("gatewayId",pid,"revision",0))).andExpect(status().isForbidden());
  mvc.perform(body(put(path).with(user("alice")),Map.of("gatewayId",pid,"revision",0))).andExpect(status().isForbidden());
  mvc.perform(body(as(put(B+"/devices/"+cid+"/gateway"),"bob"),Map.of("gatewayId",pid,"revision",0))).andExpect(status().isNotFound());
  var foreign=register(B,"bob",proxyProduct(B,"bob","foreign","GATEWAY"),"foreign");
  mvc.perform(body(as(put(path),"alice"),Map.of("gatewayId",foreign.path("clientId").asText(),"revision",0))).andExpect(status().isNotFound());
  mvc.perform(body(as(put(path),"alice"),Map.of("gatewayId",direct.path("clientId").asText(),"revision",0))).andExpect(status().isBadRequest());
  mvc.perform(body(as(put(path),"alice"),Map.of("gatewayId",pid,"revision",99))).andExpect(status().isConflict());
  mvc.perform(body(as(put(path),"alice"),Map.of("gatewayId",pid,"revision",0))).andExpect(status().isOk()).andExpect(jsonPath("$.gateway.id").value(pid));
  mvc.perform(as(get(A+"/devices/"+pid+"/topology"),"alice")).andExpect(status().isOk()).andExpect(jsonPath("$.children.total").value(1)).andExpect(jsonPath("$.children.items[0].id").value(cid));
  mvc.perform(body(as(put(path),"alice"),Map.of("gatewayId",pid,"revision",1))).andExpect(status().isConflict());
  mvc.perform(body(as(put(A+"/products/"+cp.path("id").asText()+"/draft"),"alice"),Map.of("name","child","description","","revision",cp.path("revision").asLong(),"model",cp.path("draft"),"configuration",Map.of("deviceType","DIRECT")))).andExpect(status().isConflict());
  mvc.perform(body(as(put(A+"/devices/"+pid),"alice"),Map.of("name","gateway","lifecycle","DISABLED","revision",0))).andExpect(status().isOk());
  mvc.perform(as(delete(A+"/devices/"+pid).param("revision","1"),"alice")).andExpect(status().isConflict());
  mvc.perform(as(delete(path).param("gatewayId",pid).param("revision","0"),"alice")).andExpect(status().isConflict());
  mvc.perform(as(delete(path).param("gatewayId",pid).param("revision","1"),"alice")).andExpect(status().isOk()).andExpect(jsonPath("$.gateway").isEmpty());
  mvc.perform(as(delete(A+"/devices/"+pid).param("revision","1"),"alice")).andExpect(status().isNoContent());
 }
 @Test void registeredGatewayProxiesChildRoundTripAndRevokesRoutingOnUnbind()throws Exception {
  var parent=register(A,"alice",proxyProduct(A,"alice","proxy-gateway","GATEWAY"),"gateway");
  var child=register(A,"alice",proxyProduct(A,"alice","proxy-child","CHILD"),"child");
  String pid=parent.path("clientId").asText(),cid=child.path("clientId").asText(),path=A+"/devices/"+cid;
  String topic="nebula/v1/child/"+cid;
  mvc.perform(body(as(put(path+"/gateway"),"alice"),Map.of("gatewayId",pid,"revision",0))).andExpect(status().isOk());
  rejectsDevice(cid,cid,child.path("secret").asText());
  var key=new com.nebula.iot.core.device.DeviceKey(new com.nebula.iot.core.identity.TenantId("ta"),new com.nebula.iot.core.identity.DeviceId(cid));
  var wrongParent=new com.nebula.iot.core.device.DeviceKey(new com.nebula.iot.core.identity.TenantId("tb"),new com.nebula.iot.core.identity.DeviceId(pid));
  assertTrue(connectionDirectory.resolveChild(wrongParent,key).isEmpty());
  var rogue=register(A,"alice",proxyProduct(A,"alice","rogue-gateway","GATEWAY"),"rogue");
  try(var client=connectDevice(rogue.path("clientId").asText(),rogue.path("clientId").asText(),rogue.path("secret").asText())) {
   long rejected=mqtt.rejectedMessageCount();
   client.publish(topic+"/online","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
   assertEquals(rejected+1,mqtt.rejectedMessageCount());assertTrue(client.isConnected());
  }
  var observed=new java.util.concurrent.LinkedBlockingQueue<com.nebula.iot.core.device.DeviceKey>();
  mqtt.setConnectionObserver((device,online)->{if(device.equals(key)&&online)observed.add(device);});
  var uplinks=new java.util.concurrent.LinkedBlockingQueue<com.nebula.iot.message.EventMessage>();
  var tap=eventBus.subscribe(com.nebula.iot.message.SubscriptionSpec.shared("gateway-test","device/*/property/report"),event->{uplinks.add(event);return java.util.concurrent.CompletableFuture.completedFuture(null);});
  var received=new java.util.concurrent.LinkedBlockingQueue<String>();
  try(var client=connectDevice(pid,pid,parent.path("secret").asText())) {
   client.setCallback(new org.eclipse.paho.mqttv5.client.MqttCallback(){
    public void messageArrived(String t,org.eclipse.paho.mqttv5.common.MqttMessage m){received.add(t+" "+new String(m.getPayload(),java.nio.charset.StandardCharsets.UTF_8));}
    public void disconnected(org.eclipse.paho.mqttv5.client.MqttDisconnectResponse r){}
    public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException e){}
    public void deliveryComplete(org.eclipse.paho.mqttv5.client.IMqttToken t){}
    public void connectComplete(boolean reconnect,String uri){}
    public void authPacketArrived(int reason,org.eclipse.paho.mqttv5.common.packet.MqttProperties p){}
   });
   client.publish(topic+"/online","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
   assertEquals(key,observed.poll(3,java.util.concurrent.TimeUnit.SECONDS));
   // A repeat online request keeps the existing logical session.
   client.publish(topic+"/online","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
   assertNull(observed.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS));
   String report="{\"version\":\"1.0\",\"messageId\":\"proxy-report\",\"method\":\"thing.property.report\",\"timestamp\":"+System.currentTimeMillis()+",\"data\":{\"properties\":{\"power\":true}}}";
   client.publish(topic+"/up",report.getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
   var uplink=uplinks.poll(3,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(uplink);assertEquals(cid,uplink.deviceId());assertEquals("ta",uplink.tenantId());
   var down=json.readTree(mvc.perform(body(as(post(path+"/downlinks/properties"),"alice"),Map.of("properties",Map.of("power",false)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   String delivery=received.poll(3,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(delivery);assertTrue(delivery.startsWith(topic+"/down "));
   var packet=json.readTree(delivery.substring(delivery.indexOf(' ')+1));
   String reply="{\"version\":\"1.0\",\"messageId\":\"proxy-reply\",\"method\":\"thing.property.write.reply\",\"timestamp\":"+System.currentTimeMillis()+",\"replyTo\":\""+packet.path("messageId").asText()+"\",\"result\":{\"code\":\"SUCCESS\"},\"data\":{\"properties\":{\"power\":false}}}";
   client.publish(topic+"/up",reply.getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
   mvc.perform(as(get(path+"/downlinks/"+down.path("messageId").asText()),"alice")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
   client.publish(topic+"/offline","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
   var offline=new com.nebula.iot.core.command.DeviceDownlink(new com.nebula.iot.core.identity.MessageId("offline-child"),key,"v1",java.time.Instant.now(),java.time.Instant.now().plusSeconds(10),Optional.empty(),Optional.empty(),new com.nebula.iot.core.command.DownlinkBody.PropertyWrite(Map.of("power",new com.nebula.iot.core.model.Value.BooleanValue(true))));
   assertThrows(java.util.concurrent.ExecutionException.class,()->mqtt.send(offline).toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS));
   client.publish(topic+"/online","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
   // Closing the physical connection removes child routing. Reconnecting alone never resurrects it.
   client.disconnect();
   try(var replacement=connectDevice(pid,pid,parent.path("secret").asText())) {
    assertThrows(java.util.concurrent.ExecutionException.class,()->mqtt.send(offline).toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS));
   }
   client.connect(newConnectionOptions(pid,parent.path("secret").asText()));
   client.publish(topic+"/online","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
   mvc.perform(as(delete(path+"/gateway").param("gatewayId",pid).param("revision","1"),"alice")).andExpect(status().isOk());
   assertThrows(java.util.concurrent.ExecutionException.class,()->mqtt.send(offline).toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS));
   assertTrue(client.isConnected());
  } finally { tap.close();mqtt.setConnectionObserver(presence::changed); }
 }
}

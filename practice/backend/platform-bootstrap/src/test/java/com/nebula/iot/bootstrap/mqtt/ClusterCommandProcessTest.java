package com.nebula.iot.bootstrap.mqtt;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import static org.junit.jupiter.api.Assertions.*;

/** Six real JVMs: two API, two access, two worker. Kafka and SQL are isolated test infrastructure. */
class ClusterCommandProcessTest {
    final ObjectMapper json=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"kafka","dubbo"})
    void commandSurvivesApiCrashRoutesToRemoteDeviceAndDoesNotRepeatAnUncertainSend(String mode) throws Exception {
        var fixture=new AccessClusterProcessTest();
        var broker=new EmbeddedKafkaKraftBroker(1,3,"commands-test","commands-failed");
        var db=new JdbcDataSource();String name="commands_"+UUID.randomUUID().toString().replace("-","");
        db.setURL("jdbc:h2:mem:"+name+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");db.setUser("sa");db.setPassword("test");
        try(var held=db.getConnection()) {
            var server=Server.createTcpServer("-tcpPort","0","-ifNotExists").start();
            try {
                if(mode.equals("kafka")){broker.afterPropertiesSet();fixture.kafka=broker.getBrokersAsString();}
                else fixture.kafka="dubbo";
                String url="jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/mem:"+name+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
                var apiA=fixture.start(url,"api-a","management");var apiB=fixture.start(url,"api-b","management");
                var accessA=fixture.start(url,"access-a","access");var accessB=fixture.start(url,"access-b","access");
                var jdbc=new JdbcTemplate(db);
                String model="{\"properties\":{\"power\":{\"id\":\"power\",\"schema\":{\"type\":\"BOOLEAN\",\"minimum\":null,\"maximum\":null,\"maxLength\":null},\"access\":\"READ_WRITE\"}},\"events\":{},\"operations\":{}}";
                jdbc.update("INSERT INTO tenant(id,name) VALUES ('test-tenant','Test')");
                jdbc.update("INSERT INTO device_product(id,tenant_id,scope_key,code,name,draft_model,latest_version) VALUES ('product','test-tenant','test-tenant','product','Test',?,'v1')",model);
                jdbc.update("INSERT INTO device_model_version(product_id,version,model_json) VALUES ('product','v1',?)",model);
                jdbc.update("INSERT INTO device_registration(id,tenant_id,scope_key,product_id,code,name,model_version,lifecycle,credential_hash) VALUES ('warmer-1','test-tenant','test-tenant','product','warmer','Warmer','v1','ACTIVE','unused')");
                var device=fixture.connect(accessB,1);var messages=new LinkedBlockingQueue<String>();
                device.setCallback(new Callback(messages));
                var httpA=http();var httpB=http();fixture.login(httpA,apiA.http());fixture.login(httpB,apiB.http());
                String base="/api/management/catalog/tenants/test-tenant/devices/warmer-1/downlinks";
                var submitted=post(fixture,httpA,apiA.http(),base+"/properties","{\"properties\":{\"power\":true}}");
                String messageId=submitted.path("messageId").asText();assertEquals("QUEUED",submitted.path("status").asText());
                apiA.process().destroyForcibly();assertTrue(apiA.process().waitFor(10,TimeUnit.SECONDS));
                var workerA=fixture.start(url,"worker-a","worker");var workerB=fixture.start(url,"worker-b","worker");
                assertEquals(-1,workerA.mqtt());assertEquals(-1,workerB.mqtt());
                var received=messages.poll(25,TimeUnit.SECONDS);assertNotNull(received,"Durable command must survive API process loss");
                var request=json.readTree(received);assertEquals(messageId,request.path("messageId").asText());
                reply(device,messageId,"reply-1");
                await(()->"SUCCEEDED".equals(jdbc.queryForObject("SELECT command_state FROM device_command WHERE message_id=?",String.class,messageId)),Duration.ofSeconds(10));
                var result=fixture.get(httpB,apiB.http(),base+"/"+messageId);assertEquals("SUCCEEDED",result.path("status").asText());
                // Replay both route and dispatch events after final feedback; no second device side effect.
                jdbc.update("UPDATE device_command_outbox SET published=FALSE,claim_until=NULL");
                assertNull(messages.poll(3,TimeUnit.SECONDS));
                // Withhold MQTT PUBACK, kill the owning node after observing the actual device payload.
                device.setManualAcks(true);
                var uncertain=post(fixture,httpB,apiB.http(),base+"/properties","{\"properties\":{\"power\":false}}");
                String uncertainId=uncertain.path("messageId").asText();assertNotNull(messages.poll(15,TimeUnit.SECONDS));
                accessB.process().destroyForcibly();assertTrue(accessB.process().waitFor(10,TimeUnit.SECONDS));
                var replacement=fixture.connect(accessA,1);var afterReconnect=new LinkedBlockingQueue<String>();replacement.setCallback(new Callback(afterReconnect));
                jdbc.update("UPDATE device_command SET expires_at=? WHERE message_id=?",LocalDateTime.ofInstant(Instant.now().minusSeconds(1),ZoneOffset.UTC),uncertainId);
                await(()->Set.of("UNKNOWN","TIMED_OUT").contains(jdbc.queryForObject("SELECT command_state FROM device_command WHERE message_id=?",String.class,uncertainId)),Duration.ofSeconds(10));
                jdbc.update("UPDATE device_command_outbox SET published=FALSE,claim_until=NULL");
                assertNull(afterReconnect.poll(4,TimeUnit.SECONDS),"A possible send must not be repeated after node loss");
                assertTrue(Set.of("UNKNOWN","TIMED_OUT").contains(fixture.get(httpB,apiB.http(),base+"/"+uncertainId).path("status").asText()));
            } finally {
                for(var client:fixture.clients)try{if(client.isConnected())client.disconnectForcibly(0,0,false);client.close(true);}catch(Exception ignored){}
                for(var child:fixture.processes){child.process().destroy();if(!child.process().waitFor(8,TimeUnit.SECONDS)){child.process().destroyForcibly();child.process().waitFor(8,TimeUnit.SECONDS);}}
                if(mode.equals("kafka"))broker.destroy();server.stop();
            }
        }
    }
    HttpClient http(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();}
    JsonNode post(AccessClusterProcessTest fixture,HttpClient http,int port,String path,String body)throws Exception{
        var csrf=fixture.get(http,port,"/api/management/auth/csrf");
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json").header(csrf.path("headerName").asText(),csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());
    }
    void reply(MqttClient device,String id,String replyId)throws Exception{
        var root=json.createObjectNode();root.put("version","1.0");root.put("messageId",replyId);root.put("method","thing.property.write.reply");root.put("timestamp",0);root.put("replyTo",id);
        root.putObject("result").put("code","SUCCESS");root.putObject("data").putObject("properties").put("power",true);
        device.publish("nebula/v1/up",json.writeValueAsBytes(root),1,false);
    }
    static void await(BooleanSupplier condition,Duration timeout)throws Exception{long end=System.nanoTime()+timeout.toNanos();while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(50);assertTrue(condition.getAsBoolean());}
    static final class Callback implements MqttCallback {
        final BlockingQueue<String> messages;Callback(BlockingQueue<String> messages){this.messages=messages;}
        public void messageArrived(String topic,MqttMessage message){messages.add(new String(message.getPayload(),StandardCharsets.UTF_8));}
        public void disconnected(MqttDisconnectResponse response){}public void mqttErrorOccurred(MqttException error){}
        public void deliveryComplete(IMqttToken token){}public void connectComplete(boolean reconnect,String uri){}
        public void authPacketArrived(int code,org.eclipse.paho.mqttv5.common.packet.MqttProperties properties){}
    }
}

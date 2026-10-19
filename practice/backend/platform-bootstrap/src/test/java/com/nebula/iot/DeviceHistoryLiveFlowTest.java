package com.nebula.iot;

import com.fasterxml.jackson.databind.*;
import com.nebula.iot.device.api.ProductHistoryPolicy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in end-to-end check against the running local frontend proxy, API, MQTT, Kafka worker and TDengine. */
@EnabledIfEnvironmentVariable(named="HISTORY_FLOW_BASE_URL",matches=".+")
class DeviceHistoryLiveFlowTest {
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    final String base=System.getenv("HISTORY_FLOW_BASE_URL");
    final String catalog="/catalog/platform";

    @Test void savesPolicyAndQueriesActualPartialReportsAndWholeEvents() throws Exception {
        assertEquals("127.0.0.1",URI.create(base).getHost(),"This test is limited to the local development environment");
        var csrf=call("GET","/auth/csrf",null);
        String form="username="+URLEncoder.encode(Objects.requireNonNull(System.getenv("HISTORY_FLOW_USERNAME")),StandardCharsets.UTF_8)
                +"&password="+URLEncoder.encode(Objects.requireNonNull(System.getenv("HISTORY_FLOW_PASSWORD")),StandardCharsets.UTF_8);
        var login=http.send(HttpRequest.newBuilder(URI.create(base+"/auth/login")).timeout(Duration.ofSeconds(10))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(204,login.statusCode(),"Local management login failed");
        String suffix=UUID.randomUUID().toString().substring(0,8), productId=null, deviceId=null;
        MqttClient client=null;
        try {
            var p=call("POST",catalog+"/products",Map.of("code","history_check_"+suffix,"name","历史链路自动验收 "+suffix,"description","Temporary integration fixture"));
            productId=p.path("id").asText();
            var model=json.readTree("""
                {"properties":{"temperature":{"id":"temperature","schema":{"type":"DECIMAL"},"access":"READ_ONLY"},
                "power":{"id":"power","schema":{"type":"BOOLEAN"},"access":"READ_ONLY"}},
                "events":{"fault":{"id":"fault","fields":{"reason":{"id":"reason","schema":{"type":"TEXT"},"required":true}}}},"operations":{}}
                """);
            p=call("PUT",catalog+"/products/"+productId+"/draft",Map.of("name",p.path("name").asText(),"description","Temporary integration fixture","model",model,"revision",p.path("revision").asLong()));
            p=call("POST",catalog+"/products/"+productId+"/publish",Map.of("revision",p.path("revision").asLong(),"confirmEmpty",false));
            var property=new ProductHistoryPolicy.Rule(true,"time",1,100,"every",60,false,365);
            var event=new ProductHistoryPolicy.Rule(false,"time",1,100,"every",60,false,365);
            var policy=new ProductHistoryPolicy(property,Map.of(),event,Map.of());
            String policyPath=catalog+"/products/"+productId+"/data-policy";
            var saved=call("PUT",policyPath,Map.of("revision",0,"policy",policy));
            assertTrue(saved.path("capabilities").path("enabled").asBoolean());
            assertEquals("tdengine",saved.path("capabilities").path("provider").asText());
            assertEquals(1,call("GET",policyPath,null).path("revision").asLong());
            var credential=call("POST",catalog+"/devices",Map.of("productId",productId,"code","history_check_"+suffix,"name","History check","modelVersion",p.path("latestVersion").asText()));
            deviceId=credential.path("device").path("id").asText();
            client=new MqttClient("tcp://127.0.0.1:1883",credential.path("clientId").asText(),new MemoryPersistence());
            client.setTimeToWait(5000);var options=new MqttConnectionOptions();options.setConnectionTimeout(5);options.setCleanStart(true);options.setSessionExpiryInterval(0L);
            options.setUserName(credential.path("username").asText());options.setPassword(credential.path("secret").asText().getBytes(StandardCharsets.UTF_8));client.connect(options);
            long at=System.currentTimeMillis();
            String first=report("full-"+suffix,at,"thing.property.report",Map.of("properties",Map.of("temperature",new java.math.BigDecimal("25.1234567890123456789"),"power",true)));
            client.publish("nebula/v1/up",first.getBytes(StandardCharsets.UTF_8),1,false);
            client.publish("nebula/v1/up",first.getBytes(StandardCharsets.UTF_8),1,false);
            client.publish("nebula/v1/up",report("partial-"+suffix,at+1,"thing.property.report",Map.of("properties",Map.of("temperature",26))).getBytes(StandardCharsets.UTF_8),1,false);
            client.publish("nebula/v1/up",report("event-"+suffix,at+2,"thing.event.report",Map.of("eventId","fault","fields",Map.of("reason","验收事件"))).getBytes(StandardCharsets.UTF_8),1,false);
            String query="?from="+Instant.ofEpochMilli(at-1000)+"&to="+Instant.ofEpochMilli(at+60000);
            String history=catalog+"/devices/"+deviceId+"/history/";
            var temperatures=await(history+"PROPERTY/temperature"+query,2);
            assertEquals(2,temperatures.size(),"Duplicate reports must not add rows");
            assertEquals("25.1234567890123456789",temperatures.get(1).path("payload").asText());
            assertEquals(1,await(history+"PROPERTY/power"+query,1).size(),"Partial reports must not fabricate missing values");
            var events=await(history+"EVENT/fault"+query,1);
            assertEquals("验收事件",json.readTree(events.get(0).path("payload").asText()).path("reason").asText());
        } finally {
            if(client!=null){if(client.isConnected())client.disconnect();client.close();}
            // Only resources created by this invocation are removed; test history expires after one day.
            if(deviceId!=null){var d=call("GET",catalog+"/devices/"+deviceId,null);d=call("PUT",catalog+"/devices/"+deviceId,Map.of("name",d.path("name").asText(),"lifecycle","DISABLED","revision",d.path("revision").asLong()));call("DELETE",catalog+"/devices/"+deviceId+"?revision="+d.path("revision").asLong(),null);}
            if(productId!=null){var p=call("GET",catalog+"/products/"+productId,null);p=call("PUT",catalog+"/products/"+productId+"/status",Map.of("enabled",false,"revision",p.path("revision").asLong()));call("DELETE",catalog+"/products/"+productId+"?revision="+p.path("revision").asLong(),null);}
        }
    }
    JsonNode await(String path,int count) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        do {var items=call("GET",path,null).path("items");if(items.size()>=count)return items;Thread.sleep(250);} while(System.nanoTime()<until);
        throw new AssertionError("History did not arrive through the live pipeline");
    }
    String report(String id,long at,String method,Object data) throws Exception {return json.writeValueAsString(Map.of("version","1.0","messageId",id,"timestamp",at,"method",method,"data",data));}
    JsonNode call(String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(!method.equals("GET")){var csrf=call("GET","/auth/csrf",null);request.header(csrf.path("headerName").asText(),csrf.path("token").asText());}
        request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode()>=200&&response.statusCode()<300,()->method+" "+path+" returned "+response.statusCode());
        return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
    }
}

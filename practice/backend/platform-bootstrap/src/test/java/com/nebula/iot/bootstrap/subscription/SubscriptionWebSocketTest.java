package com.nebula.iot.bootstrap.subscription;

import com.fasterxml.jackson.databind.*;
import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.device.api.CatalogViews.Device;
import com.nebula.iot.message.*;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:subscriptions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test",
    "nebula.redis.enabled=false","nebula.mqtt.enabled=false"})
@ActiveProfiles("management")
class SubscriptionWebSocketTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired EventBus bus;
    @MockitoBean CatalogService catalog;
    HttpClient http;
    URI uri(String path) { return URI.create("http://localhost:"+port+"/api/management"+path); }
    HttpResponse<String> post(String path,String contentType,String body) throws Exception {
        var csrf=json.readTree(http.send(HttpRequest.newBuilder(uri("/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
        return http.send(HttpRequest.newBuilder(uri(path)).header(csrf.get("headerName").asText(),csrf.get("token").asText())
                .header("Content-Type",contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String ticket() throws Exception {
        var response=post("/realtime/tickets","application/json","{\"devices\":[{\"tenantId\":\"ta\",\"deviceId\":\"d1\"}]}");
        assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).get("ticket").asText();
    }
    @Test void realUpgradeRequiresLoginCsrfTicketAndSameOriginAndDeliversBusNotifications() throws Exception {
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin) VALUES ('ws-root','ws-root',?,TRUE)",passwords.encode("test-password"));
        when(catalog.device("ws-root","ta","d1")).thenReturn(new Device("d1","ta","p","Product","code","Device","v1","ACTIVE",1,1));
        http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var endpoint="ws://localhost:"+port+"/api/management/realtime/socket?ticket=";
        assertThrows(CompletionException.class,()->http.newWebSocketBuilder().buildAsync(URI.create(endpoint+"missing"),new WebSocket.Listener(){}).join());
        assertEquals(204,post("/auth/login","application/x-www-form-urlencoded","username=ws-root&password=test-password").statusCode());
        assertEquals(403,http.send(HttpRequest.newBuilder(uri("/realtime/tickets")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        String deniedTicket=ticket();
        assertThrows(CompletionException.class,()->http.newWebSocketBuilder().header("Origin","https://untrusted.example").buildAsync(URI.create(endpoint+deniedTicket),new WebSocket.Listener(){}).join());
        var messages=new LinkedBlockingQueue<String>();var closed=new CompletableFuture<Integer>();
        String token=ticket();
        var socket=http.newWebSocketBuilder().buildAsync(URI.create(endpoint+token),new WebSocket.Listener(){
            final StringBuilder parts=new StringBuilder();
            @Override public void onOpen(WebSocket ws){ws.request(1);}
            @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){
                parts.append(data);if(last){messages.add(parts.toString());parts.setLength(0);}ws.request(1);return null;
            }
            @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){closed.complete(status);return null;}
        }).get(5,TimeUnit.SECONDS);
        assertTrue(messages.poll(5,TimeUnit.SECONDS).contains("ready"));
        assertThrows(CompletionException.class,()->http.newWebSocketBuilder().buildAsync(URI.create(endpoint+token),new WebSocket.Listener(){}).join());
        var now=Instant.now();bus.publish(new EventMessage("e1","external/device/shadow",now,now,"ta","d1","v1","m1","APPLICATION",Map.of(),Map.of())).toCompletableFuture().join();
        var notice=json.readTree(messages.poll(5,TimeUnit.SECONDS));assertEquals("invalidate",notice.get("type").asText());assertEquals("d1",notice.get("deviceId").asText());assertFalse(notice.has("payload"));
        assertEquals(204,post("/auth/logout","application/x-www-form-urlencoded","").statusCode());
        bus.publish(new EventMessage("e2","external/device/shadow",now,now,"ta","d1","v1","m2","APPLICATION",Map.of(),Map.of())).toCompletableFuture().join();
        assertEquals(1008,closed.get(5,TimeUnit.SECONDS));socket.abort();
    }
}

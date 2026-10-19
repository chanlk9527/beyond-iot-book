package com.nebula.verification;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import static org.junit.jupiter.api.Assertions.*;

/** Launches the actual three executable jars, with only a test H2 driver and shadow adapter added. */
class SeparatedServicesIT {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Process> processes = new ArrayList<>();
    private final Path root = Path.of(System.getProperty("backend.root")).toAbsolutePath().normalize();
    private final String version = System.getProperty("artifact.version");
    private String brokerAddress;
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(3)).build();
    private record Child(Process process, int http, int mqtt, Path log) { }

    @Test void realMqttCrossesAccessWorkerAndManagementAndRecoversOfflineBacklog() throws Exception {
        var kafka = new EmbeddedKafkaKraftBroker(1, 3, "services-events", "services-failed");
        var database = new JdbcDataSource();
        String name = "services_" + UUID.randomUUID().toString().replace("-", "");
        database.setURL("jdbc:h2:mem:"+name+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        database.setUser("sa"); database.setPassword("test");
        Server server = null; WebSocket socket = null; MqttClient device = null;
        try (var held = database.getConnection()) {
            kafka.afterPropertiesSet();
            brokerAddress=kafka.getBrokersAsString();
            server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
            String url = "jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/mem:"+name+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
            var management = start("management", url, kafka.getBrokersAsString());
            var jdbc = new JdbcTemplate(database);
            seed(jdbc);
            var access = start("access", url, kafka.getBrokersAsString());
            assertEquals(403, get(access.http(),"/api/management/auth/csrf").statusCode());
            login(management.http());
            var tickets = post(management.http(),"/api/management/realtime/tickets","application/json",
                    "{\"devices\":[{\"tenantId\":\"ta\",\"deviceId\":\"d1\"}]}");
            assertEquals(200,tickets.statusCode(),tickets.body());
            var messages = new LinkedBlockingQueue<String>();
            socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+management.http()
                    +"/api/management/realtime/socket?ticket="+json.readTree(tickets.body()).path("ticket").asText()),new WebSocket.Listener() {
                final StringBuilder text = new StringBuilder();
                public void onOpen(WebSocket ws) { ws.request(1); }
                public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
                    text.append(data);
                    if(last) { String message=text.toString(); text.setLength(0); messages.add(message);
                        if(message.contains("\"ping\""))ws.sendText("pong",true); }
                    ws.request(1); return null;
                }
            }).get(10,TimeUnit.SECONDS);
            assertTrue(Objects.requireNonNull(messages.poll(10,TimeUnit.SECONDS)).contains("ready"));
            device = new MqttClient("tcp://127.0.0.1:"+access.mqtt(),"d1",new MemoryPersistence());
            device.setTimeToWait(10000);
            var options = new MqttConnectionOptions(); options.setConnectionTimeout(5); options.setCleanStart(true);
            options.setUserName("d1"); options.setPassword("device-password".getBytes(StandardCharsets.UTF_8)); device.connect(options);
            report(device,"m1",true);
            assertKafkaReport(kafka.getBrokersAsString(),"m1");
            // No worker exists yet: access publishes durably, management must not consume as a worker.
            Thread.sleep(1000);
            assertEquals(0,shadow(management.http()).path("version").asInt());
            var worker = start("worker", url, kafka.getBrokersAsString());
            assertEquals(403,get(worker.http(),"/api/management/auth/csrf").statusCode());
            awaitStoredReport(jdbc,"m1");
            assertKafkaNotice(kafka.getBrokersAsString());
            assertTrue(awaitShadowNotice(messages).contains("d1"));
            var state = shadow(management.http());
            assertTrue(state.path("reported").path("power").path("value").asBoolean(),state.toString());
            assertEquals("worker",jdbc.queryForObject("SELECT writer FROM fixture_shadow WHERE device='d1'",String.class));
            stop(worker.process());
            messages.clear();
            report(device,"m2",false);
            Thread.sleep(500);
            assertTrue(shadow(management.http()).path("reported").path("power").path("value").asBoolean());
            start("worker",url,kafka.getBrokersAsString());
            awaitStoredReport(jdbc,"m2");
            awaitShadowNotice(messages);
            var recovered=shadow(management.http());
            assertFalse(recovered.path("reported").path("power").path("value").asBoolean(),recovered.toString());
            assertEquals("m2",recovered.path("reported").path("power").path("messageId").asText());
        } finally {
            if(socket!=null)socket.abort();
            if(device!=null)try{if(device.isConnected())device.disconnectForcibly(0,0,false);device.close(true);}catch(Exception ignored){}
            for(var process:processes)stop(process);
            if(server!=null)server.stop();
            kafka.destroy();
        }
    }

    private Child start(String role,String database,String kafka) throws Exception {
        int port=port(), mqtt=role.equals("access")?port():-1;
        Path jar=root.resolve("platform-services/"+role+"-service/target/"+role+"-service-"+version+"-exec.jar");
        assertTrue(Files.isRegularFile(jar),"Build service jars before running verify: "+jar);
        String h2=Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path fixture=root.resolve("platform-services/service-verification/target/service-verification-"+version+"-tests.jar");
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
                "-Xmx256m","-Dloader.path="+h2+","+fixture,"-cp",jar.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher",
                "--spring.datasource.url="+database,"--spring.datasource.username=sa","--spring.datasource.password=test",
                "--spring.datasource.driver-class-name=org.h2.Driver","--DATABASE_PASSWORD=test","--server.port="+port,
                "--nebula.redis.enabled=false","--nebula.eventbus.node-id="+role+"-"+UUID.randomUUID(),
                "--spring.kafka.bootstrap-servers="+kafka,"--nebula.eventbus.topic=services-events","--nebula.eventbus.failure-topic=services-failed",
                "--spring.kafka.consumer.properties[session.timeout.ms]=6000",
                "--spring.kafka.consumer.properties[heartbeat.interval.ms]=1000",
                "--nebula.management.initial-account.username=service-admin","--nebula.management.initial-account.password=test-password",
                "--nebula.management.initial-account.platform-admin=true",
                "--spring.main.sources="+ShadowFixtureInitializer.StoreConfiguration.class.getName(),"--logging.level.root=WARN"));
        if(mqtt>0)command.add("--nebula.mqtt.port="+mqtt);
        Path log=root.resolve("platform-services/service-verification/target/"+role+"-"+UUID.randomUUID()+".log");
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        // Isolate the test from the caller's development credentials and role overrides.
        builder.environment().keySet().removeIf(key -> key.startsWith("SPRING_") || key.startsWith("NEBULA_")
                || key.startsWith("EVENTBUS_") || key.startsWith("REDIS_") || key.equals("MQTT_ENABLED"));
        var process=builder.start();processes.add(process);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
        while(System.nanoTime()<deadline && process.isAlive()) {
            try {if(get(port,"/actuator/health/readiness").statusCode()==200) return new Child(process,port,mqtt,log);}
            catch(Exception pending) { }
            Thread.sleep(200);
        }
        fail("Service failed to start; log: "+log); return null;
    }

    private void seed(JdbcTemplate jdbc) {
        String model="{\"properties\":{\"power\":{\"id\":\"power\",\"schema\":{\"type\":\"BOOLEAN\"},\"access\":\"READ_WRITE\"}},\"events\":{},\"operations\":{}}";
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','Service test')");
        jdbc.update("INSERT INTO device_product(id,tenant_id,scope_key,code,name,draft_model,latest_version) VALUES ('p1','ta','ta','warmer','Warmer',?,'v1')",model);
        jdbc.update("INSERT INTO device_model_version(product_id,version,model_json) VALUES ('p1','v1',?)",model);
        String password=PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("device-password");
        jdbc.update("INSERT INTO device_registration(id,tenant_id,scope_key,product_id,code,name,model_version,lifecycle,credential_hash) VALUES ('d1','ta','ta','p1','warmer','Warmer','v1','ACTIVE',?)",password);
    }
    private void report(MqttClient device,String id,boolean power) throws Exception {
        String payload="{\"version\":\"1.0\",\"method\":\"thing.property.report\",\"messageId\":\""+id+"\",\"timestamp\":"+Instant.now().toEpochMilli()+",\"data\":{\"properties\":{\"power\":"+power+"}}}";
        var message=new org.eclipse.paho.mqttv5.common.MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        var token=device.getTopic("nebula/v1/up").publish(message);
        token.waitForCompletion(10000);
        for(int reason:token.getReasonCodes())assertTrue(reason<128,"MQTT report rejected: "+reason);
    }
    private void assertKafkaReport(String broker,String id) throws Exception {
        var config=Map.<String,Object>of("bootstrap.servers",broker,"group.id","observer-"+UUID.randomUUID(),"auto.offset.reset","earliest");
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<>(config,
                new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
            consumer.subscribe(List.of("services-events"));
            var codec=new com.nebula.iot.message.kafka.EventJsonCodec();
            var observed=new ArrayList<String>();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofMillis(200))) {
                var event=codec.decode(record.value()); observed.add(event.topic()+":"+event.messageId());
                if(event.messageId().equals(id)) {
                    assertEquals("device/d1/property/report",event.topic());
                    assertEquals("ta",event.tenantId()); return;
                }
            }
            fail("Accepted MQTT report absent from Kafka: "+observed);
        }
    }
    private void assertKafkaNotice(String broker) {
        var config=Map.<String,Object>of("bootstrap.servers",broker,"group.id","notice-observer-"+UUID.randomUUID(),"auto.offset.reset","earliest");
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<>(config,
                new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
            consumer.subscribe(List.of("services-events"));
            var codec=new com.nebula.iot.message.kafka.EventJsonCodec();
            var observed=new ArrayList<String>();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofMillis(200))) {
                var event=codec.decode(record.value()); observed.add(event.topic());
                if(event.topic().equals("external/device/shadow"))return;
            }
            fail("Worker updated shadow but did not publish state notification: "+observed);
        }
    }
    private JsonNode shadow(int port) throws Exception {
        var response=get(port,"/api/management/catalog/tenants/ta/devices/d1/shadow");
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    private void awaitStoredReport(JdbcTemplate jdbc,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            var rows=jdbc.queryForList("SELECT document FROM fixture_shadow WHERE device='d1'",String.class);
            if(!rows.isEmpty() && rows.getFirst().contains(id))return;
            Thread.sleep(100);
        }
        fail("Worker did not update shared shadow for "+id+"; "+failureDetails());
    }
    private String failureDetails() {
        var config=Map.<String,Object>of("bootstrap.servers",brokerAddress,"group.id","failure-observer-"+UUID.randomUUID(),"auto.offset.reset","earliest");
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<>(config,
                new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
            consumer.subscribe(List.of("services-failed"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofMillis(200))) {
                var details=new StringBuilder();
                for(var header:record.headers())if(header.key().contains("exception"))
                    details.append(header.key()).append('=').append(new String(header.value(),StandardCharsets.UTF_8)).append(';');
                return details.toString();
            }
            return "No retained failure record";
        }
    }
    private String awaitShadowNotice(BlockingQueue<String> messages) throws Exception {
        var observed=new ArrayList<String>();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline) {
            String message=messages.poll(1,TimeUnit.SECONDS);
            if(message!=null)observed.add(message);
            if(message!=null && "shadow".equals(json.readTree(message).path("kind").asText()))return message;
        }
        fail("No cross-process shadow notification; received "+observed); return null;
    }
    private void login(int port) throws Exception {
        var result=post(port,"/api/management/auth/login","application/x-www-form-urlencoded","username=service-admin&password=test-password");
        assertEquals(204,result.statusCode(),result.body());
    }
    private HttpResponse<String> post(int port,String path,String contentType,String body) throws Exception {
        var csrf=json.readTree(get(port,"/api/management/auth/csrf").body());
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type",contentType).header(csrf.path("headerName").asText(),csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(int port,String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private static int port() throws Exception {try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}}
    private static void stop(Process process) throws InterruptedException {
        if(process.isAlive()){process.destroy();if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(8,TimeUnit.SECONDS));}}
    }
}

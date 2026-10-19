package com.nebula.iot.bootstrap.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.cluster.JdbcNodeDirectory;
import com.nebula.iot.gateway.JdbcDeviceSessionDirectory;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Separate JVMs, real TCP database, real MQTT, real authenticated management API. */
class AccessClusterProcessTest {
    private final ObjectMapper json=new ObjectMapper();
    String kafka;
    String stableClasspath;
    final List<Child> processes=new ArrayList<>();
    final List<MqttClient> clients=new ArrayList<>();
    record Child(Process process,String nodeId,int mqtt,int http,StringBuilder log) {}

    @Test void membershipTakeoverDrainAndCrashRecoveryAcrossProcesses() throws Exception {
        var database=new JdbcDataSource();
        String name="cluster_process_"+UUID.randomUUID().toString().replace("-","");
        database.setURL("jdbc:h2:mem:"+name+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        database.setUser("sa");database.setPassword("test");
        try(var held=database.getConnection()) {
            var server=Server.createTcpServer("-tcpPort","0","-ifNotExists").start();
            try {
                String url="jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/mem:"+name+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
                var a=start(url,"node-a","access");var b=start(url,"node-b","access");
                var management=start(url,"management","management");
                var worker=start(url,"worker","worker");
                assertEquals(-1,management.mqtt());assertEquals(-1,worker.mqtt());
                assertEquals(403,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+a.http()+"/api/management/access/nodes")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                var jdbc=new JdbcTemplate(database);
                var tx=new TransactionTemplate(new DataSourceTransactionManager(database));
                var nodes=new JdbcNodeDirectory(jdbc,tx,Duration.ofSeconds(6));
                var sessions=new JdbcDeviceSessionDirectory(jdbc,tx,"observer",Duration.ofSeconds(6));
                assertEquals(2,nodes.list(10).size());

                var old=connect(a,1);
                var device=new DeviceKey(new TenantId("test-tenant"),new DeviceId("warmer-1"));
                var first=sessions.owner(device);assertEquals(a.nodeId(),first.nodeId());
                var replacement=connect(b,1);
                await(()->!old.isConnected(),Duration.ofSeconds(8));
                var owner=sessions.owner(device);
                assertEquals(b.nodeId(),owner.nodeId());assertTrue(owner.epoch()>first.epoch());
                // The old process has now run its disconnect/release callback.
                assertEquals(owner.sessionId(),sessions.owner(device).sessionId());

                var aClients=new ArrayList<MqttClient>();
                for(int i=2;i<=6;i++)aClients.add(connect(a,i));
                var http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
                login(http,management.http());
                var overview=get(http,management.http(),"/api/management/access/nodes");
                assertEquals("CLUSTER_MEMBERSHIP",overview.path("scope").asText());
                assertEquals(2,overview.path("nodes").size());
                assertEquals(6,overview.path("onlineDevices").asInt());
                var token=get(http,management.http(),"/api/management/auth/csrf");
                String drain="{\"operationId\":\"test-drain\",\"revision\":0,\"batchSize\":1,\"timeoutSeconds\":20}";
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management.http()+"/api/management/access/nodes/"+a.nodeId()+"/drain"))
                        .header("Content-Type","application/json").header(token.path("headerName").asText(),token.path("token").asText())
                        .POST(HttpRequest.BodyPublishers.ofString(drain)).build();
                assertEquals(202,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
                // No CSRF token means no maintenance operation, even for a platform administrator.
                assertEquals(403,http.send(HttpRequest.newBuilder(request.uri()).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(drain)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                await(()->aClients.stream().filter(MqttClient::isConnected).count()<5,Duration.ofSeconds(5));
                assertEquals(503,probe(a.http(),"readiness"));
                assertEquals(200,probe(a.http(),"liveness"));
                assertThrows(MqttException.class,()->connect(a,7));

                var survivor=aClients.stream().filter(MqttClient::isConnected).findFirst().orElseThrow();
                var received=new LinkedBlockingQueue<String>();
                survivor.setCallback(new Callback(received));
                survivor.publish("nebula/v1/up","{\"version\":\"1.0\",\"method\":\"system.time.request\",\"messageId\":\"drain-time\",\"timestamp\":0,\"data\":{\"clientSendTime\":1}}".getBytes(StandardCharsets.UTF_8),1,false);
                assertNotNull(received.poll(3,TimeUnit.SECONDS),"Existing connection must still receive a downlink during drain");
                await(()->nodes.operation("test-drain").state().equals("SUCCEEDED"),Duration.ofSeconds(15));
                assertTrue(replacement.isConnected());
                assertEquals("DRAINING",nodes.find(a.nodeId()).state());
                var resumeToken=get(http,management.http(),"/api/management/auth/csrf");
                String resume="{\"operationId\":\"test-resume\",\"revision\":1}";
                var resumeResult=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management.http()+"/api/management/access/nodes/"+a.nodeId()+"/resume"))
                        .header("Content-Type","application/json").header(resumeToken.path("headerName").asText(),resumeToken.path("token").asText())
                        .POST(HttpRequest.BodyPublishers.ofString(resume)).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,resumeResult.statusCode(),resumeResult.body());
                assertEquals("RESUME",nodes.operation("test-resume").type());
                long resumeDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                MqttClient resumed=null;
                while(resumed==null && System.nanoTime()<resumeDeadline){try{resumed=connect(a,7);}catch(MqttException retry){Thread.sleep(50);}}
                assertNotNull(resumed,"Resumed node must accept new connections");
                assertEquals(200,probe(a.http(),"readiness"));

                b.process().destroyForcibly();assertTrue(b.process().waitFor(10,TimeUnit.SECONDS));
                await(()->sessions.owner(device)==null,Duration.ofSeconds(10));
                var next=start(url,"node-b","access");
                assertNotEquals(b.nodeId(),next.nodeId());
                                var recovered=connect(next,1);assertEquals(next.nodeId(),sessions.owner(device).nodeId());
                // Lose the actual shared storage connection: no process may keep serving its old lease.
                server.stop();
                await(()->!recovered.isConnected(),Duration.ofSeconds(12));
                assertThrows(MqttException.class,()->connect(next,8));
                assertThrows(IllegalStateException.class,()->nodes.heartbeat(b.nodeId(),0,"null"));
            } finally {
                for(var client:clients){try{if(client.isConnected())client.disconnectForcibly(0,0,false);client.close(true);}catch(Exception ignored){}}
                for(var child:processes){child.process().destroy();if(!child.process().waitFor(8,TimeUnit.SECONDS)){child.process().destroyForcibly();child.process().waitFor(8,TimeUnit.SECONDS);}}
                server.stop();
            }
        }
    }
    Child start(String database,String name,String role) throws Exception {
        String binary=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=processClasspath();
        var command=new ArrayList<String>(List.of(binary,"-Xmx256m","-Duser.timezone="+(name.equals("node-a")?"UTC":"Pacific/Honolulu"),"-cp",classpath,ClusterNodeProcess.class.getName(),database,name,role));
        if(kafka!=null)command.add(kafka);
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        var log=new StringBuilder();var ready=new CompletableFuture<Child>();
        var placeholder=new Child(process,"",0,0,log);processes.add(placeholder);
        Thread.ofPlatform().daemon().start(()->{
            try(var reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=reader.readLine())!=null){
                    synchronized(log){if(log.length()>16_000)log.delete(0,8000);log.append(line).append('\n');}
                    if(line.startsWith("CLUSTER_READY ")){var parts=line.split(" ");ready.complete(new Child(process,parts[1],Integer.parseInt(parts[2]),Integer.parseInt(parts[3]),log));}
                }
                if(!ready.isDone())ready.completeExceptionally(new IllegalStateException(log.toString()));
            }catch(Exception error){ready.completeExceptionally(error);}
        });
        try{return ready.get(60,TimeUnit.SECONDS);}catch(Exception error){throw new AssertionError("Node failed to start: "+log,error);}
    }
    private synchronized String processClasspath() throws Exception {
        if(stableClasspath!=null)return stableClasspath;
        var entries=new ArrayList<String>();
        // Snapshot compiled directories so concurrent local builds cannot replace child-JVM classes mid-startup.
        var folder=java.nio.file.Files.createTempDirectory("iot-cluster-test-");
        folder.toFile().deleteOnExit();
        int number=0;
        for(var value:System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            var path=Path.of(value);
            if(java.nio.file.Files.isDirectory(path)) {
                var archive=folder.resolve("classes-"+(number++)+".jar");
                try(var output=new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(archive));var files=java.nio.file.Files.walk(path)) {
                    for(var file:files.filter(file->!file.equals(path)).toList()) {
                        boolean directory=java.nio.file.Files.isDirectory(file);
                        output.putNextEntry(new java.util.jar.JarEntry(path.relativize(file).toString().replace('\\','/')+(directory?"/":"")));
                        if(!directory)java.nio.file.Files.copy(file,output);
                        output.closeEntry();
                    }
                }
                archive.toFile().deleteOnExit();entries.add(archive.toString());
            } else entries.add(value);
        }
        stableClasspath=String.join(java.io.File.pathSeparator,entries);return stableClasspath;
    }
    MqttClient connect(Child child,int number) throws Exception {
        var client=new MqttClient("tcp://127.0.0.1:"+child.mqtt(),"client-"+number,new MemoryPersistence());clients.add(client);
        client.setTimeToWait(5000);var options=new MqttConnectionOptions();options.setConnectionTimeout(3);options.setCleanStart(true);options.setSessionExpiryInterval(0L);
        options.setUserName("user-"+number);options.setPassword("test-password".getBytes(StandardCharsets.UTF_8));client.connect(options);return client;
    }
    JsonNode get(HttpClient client,int port,String path) throws Exception {
        var result=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,result.statusCode(),result.body());return json.readTree(result.body());
    }
    private int probe(int port,String name) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/"+name))
                .timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    void login(HttpClient client,int port) throws Exception {
        var csrf=get(client,port,"/api/management/auth/csrf");
        var result=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/management/auth/login"))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=cluster-admin&password=test-password")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(204,result.statusCode(),result.body());
    }
    private static void await(BooleanSupplier condition,Duration timeout) throws Exception {
        long end=System.nanoTime()+timeout.toNanos();while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(50);assertTrue(condition.getAsBoolean());
    }
    private static class Callback implements MqttCallback {
        final BlockingQueue<String> messages;Callback(BlockingQueue<String> messages){this.messages=messages;}
        public void messageArrived(String topic,MqttMessage message){messages.add(new String(message.getPayload(),StandardCharsets.UTF_8));}
        public void disconnected(MqttDisconnectResponse response){}
        public void mqttErrorOccurred(MqttException error){}
        public void deliveryComplete(IMqttToken token){}
        public void connectComplete(boolean reconnect,String serverURI){}
        public void authPacketArrived(int code,org.eclipse.paho.mqttv5.common.packet.MqttProperties properties){}
    }
}

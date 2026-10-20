package com.nebula.iot.message.dubbo;

import com.nebula.iot.message.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.nebula.iot.message.dubbo.DubboEventBusTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in with -Dnacos.test.address=host:8848. Uses a unique service and isolated H2, never business data. */
@EnabledIfSystemProperty(named="nacos.test.address", matches=".+")
class NacosEventBusIntegrationTest {
    private NacosEventNodeDiscovery discovery(String service) {
        return new NacosEventNodeDiscovery(System.getProperty("nacos.test.address"),System.getProperty("nacos.test.namespace",""),
                "BEYOND_IOT_TEST",service,System.getProperty("nacos.test.username",""),System.getProperty("nacos.test.password",""),"test");
    }
    @Test void discoversRealInstancesIgnoresSqlAddressesAndRecoversOfflineSharedWork() throws Exception {
        var ds=database(); var jdbc=new JdbcTemplate(ds); var count=new AtomicInteger();
        String service="eventbus-test-"+UUID.randomUUID();
        var senderDiscovery=discovery(service);var receiverDiscovery=discovery(service);
        try(var sender=new DubboEventBus(routes(ds),"test","sender","127.0.0.1",port(),TOKEN,Duration.ofSeconds(2),1000,senderDiscovery);
            var receiver=new DubboEventBus(routes(ds),"test","receiver","127.0.0.1",port(),TOKEN,Duration.ofSeconds(2),1000,receiverDiscovery)) {
            var sub=receiver.subscribe(SubscriptionSpec.shared("nacos-shared","device/**"),e->{count.incrementAndGet();return CompletableFuture.completedFuture(null);});
            sender.start();receiver.start();sub.ready().toCompletableFuture().get(20,TimeUnit.SECONDS);
            // A wrong legacy address must never be used as a fallback in Nacos mode.
            jdbc.update("UPDATE event_route_node SET endpoint='127.0.0.1:1'");
            Thread.sleep(700);
            sender.publish(event("nacos-first","t1","d1")).toCompletableFuture().get(5,TimeUnit.SECONDS);
            await(()->count.get()==1 && sender.pendingCount()==0);
            assertEquals("receiver",sender.inspectNode("receiver","127.0.0.1:1").nodeId());
            receiver.close();
            await(()->!senderDiscovery.endpoints().containsKey("receiver"));
            sender.publish(event("offline","t1","d2")).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(1,sender.pendingCount());
            try(var replacement=new DubboEventBus(routes(ds),"test","replacement","127.0.0.1",port(),TOKEN,Duration.ofSeconds(2),1000,discovery(service))) {
                var resumed=replacement.subscribe(SubscriptionSpec.shared("nacos-shared","device/**"),e->{count.incrementAndGet();return CompletableFuture.completedFuture(null);});
                replacement.start();resumed.ready().toCompletableFuture().get(20,TimeUnit.SECONDS);
                await(()->count.get()==2 && sender.pendingCount()==0);
            }
            senderDiscovery.close();
            assertFalse(sender.healthy());
            assertThrows(CompletionException.class,()->sender.publish(event("registry-unavailable","t1","d1")).toCompletableFuture().join());
        }
    }
    @Test void publishesAcrossJvmUsingNacosDiscovery() throws Exception {
        var server=org.h2.tools.Server.createTcpServer("-tcpPort","0","-ifNotExists").start();
        Process process=null;
        String service="eventbus-fork-"+UUID.randomUUID();
        try {
            var ds=database();
            // Initialize a distinct TCP-accessible database with the existing test migrations.
            ds.setURL("jdbc:h2:tcp://localhost:"+server.getPort()+"/mem:nacos"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/message/V26__dubbo_event_routing.sql"),
                    new org.springframework.core.io.ClassPathResource("db/message/V27__dynamic_route_lifecycle.sql"),
                    new org.springframework.core.io.ClassPathResource("db/message/V28__event_source_scope.sql")).execute(ds);
            var delivered=new CompletableFuture<Void>();
            try(var receiver=new DubboEventBus(routes(ds),"test","receiver","127.0.0.1",port(),TOKEN,Duration.ofSeconds(2),1000,discovery(service))) {
                receiver.subscribe(SubscriptionSpec.shared("cross-jvm","device/**"),e->{delivered.complete(null);return CompletableFuture.completedFuture(null);});receiver.start();
                var output=java.nio.file.Path.of("target","forked-nacos.log").toAbsolutePath();
                var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),
                        "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ForkedDubboPublisher.class.getName(),
                        ds.getURL(),Integer.toString(port()),System.getProperty("nacos.test.address"),service,
                        System.getProperty("nacos.test.namespace",""))
                        .redirectErrorStream(true).redirectOutput(output.toFile());
                builder.environment().put("NACOS_TEST_USERNAME",System.getProperty("nacos.test.username",""));
                builder.environment().put("NACOS_TEST_PASSWORD",System.getProperty("nacos.test.password",""));
                process=builder.start();
                assertTrue(process.waitFor(45,TimeUnit.SECONDS),"Forked publisher timeout");
                assertEquals(0,process.exitValue(),"See target/forked-nacos.log");
                delivered.get(15,TimeUnit.SECONDS);assertTrue(receiver.receivedRpcCount()>0);
            }
        } finally {if(process!=null && process.isAlive())process.destroyForcibly();server.stop();}
    }
}

package com.nebula.iot.message.dubbo;

import com.nebula.iot.message.*;
import com.nebula.iot.message.routing.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class DubboEventBusTest {
    static final String TOKEN="test-only-dubbo-credential-32-characters";
    static JdbcDataSource database() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");ds.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("db/message/V26__dubbo_event_routing.sql"),new ClassPathResource("db/message/V27__dynamic_route_lifecycle.sql"),new ClassPathResource("db/message/V28__event_source_scope.sql")).execute(ds);
        return ds;
    }
    static JdbcEventRoutes routes(JdbcDataSource ds) {
        return new JdbcEventRoutes(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)),"test",Duration.ofSeconds(10));
    }
    static int port() throws Exception {try(var socket=new ServerSocket(0)){return socket.getLocalPort();}}
    static DubboEventBus bus(JdbcDataSource ds,String node) throws Exception {
        return new DubboEventBus(routes(ds),"test",node,"127.0.0.1",port(),TOKEN,Duration.ofSeconds(2),1000);
    }
    static EventMessage event(String id,String tenant,String device) {
        var time=Instant.parse("2026-10-20T00:00:00Z");
        return new EventMessage(id,"device/"+device+"/property/report",time,time,tenant,device,"v1",id,"APPLICATION",Map.of(),Map.of("temperature",70));
    }
    static void await(BooleanSupplier predicate) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(!predicate.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(25);
        assertTrue(predicate.getAsBoolean(),"Condition did not become true");
    }
    @Test void indexedRoutingHasNoRpcOrDeliveryRowsForUninterestedDevicesAndIsTenantSafe() throws Exception {
        var ds=database();var jdbc=new JdbcTemplate(ds);AtomicInteger received=new AtomicInteger();
        try(var publisher=bus(ds,"publisher");var receiver=bus(ds,"receiver");var unrelated=bus(ds,"unrelated")) {
            var selected=receiver.subscribe(SubscriptionSpec.shared("rules-v1","device/*/property/report"),new EventSelector("t1",Set.of("d1")),e->{received.incrementAndGet();return CompletableFuture.completedFuture(null);});
            publisher.start();receiver.start();unrelated.start();selected.ready().toCompletableFuture().get(15,TimeUnit.SECONDS);
            for(int i=0;i<100;i++)assertEquals(0,publisher.publish(event("ignored-"+i,"t1","d2")).toCompletableFuture().join().subscriberCount());
            assertEquals(0,publisher.publish(event("other-tenant","t2","d1")).toCompletableFuture().join().subscriberCount());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM event_route_delivery",Integer.class));
            assertEquals(0,receiver.receivedRpcCount());assertEquals(0,unrelated.receivedRpcCount());
            var receiverAddress=routes(ds).overview().nodes().stream().filter(n->n.id().equals("receiver")).findFirst().orElseThrow().endpoint();
            var remoteSnapshot=publisher.inspectNode(receiverAddress);
            assertEquals("receiver",remoteSnapshot.nodeId());
            assertEquals(1,remoteSnapshot.subscriptions());
            assertEquals(101,publisher.runtimeSnapshot().published());
            assertEquals(0,publisher.runtimeSnapshot().matched());
            var message=event("match","t1","d1");
            assertEquals(PublishResult.Confirmation.DURABLY_ROUTED,publisher.publish(message).toCompletableFuture().join().confirmation());
            await(()->received.get()==1 && publisher.pendingCount()==0);
            publisher.publish(message).toCompletableFuture().join();
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM event_route_delivery",Integer.class));
            assertEquals(1,received.get());assertEquals(0,unrelated.receivedRpcCount());
            assertTrue(receiver.receivedRpcCount()>0);
            assertThrows(CompletionException.class,()->publisher.publish(new EventMessage(message.eventId(),message.topic(),message.occurredAt(),message.receivedAt(),"t1","d1","v1",message.messageId(),"APPLICATION",Map.of(),Map.of("temperature",80))).toCompletableFuture().join());
            var batch=new ArrayList<CompletableFuture<PublishResult>>();
            for(int i=0;i<48;i++)batch.add(publisher.publish(event("batch-"+i,"t1","d1")).toCompletableFuture());
            CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).join();
            await(()->received.get()==49 && publisher.pendingCount()==0);
            assertEquals(0,unrelated.receivedRpcCount());
        }
    }
    @Test void sharedSubscriptionSurvivesAllReceiversStoppingAndBacklogRecovers() throws Exception {
        var ds=database();AtomicInteger received=new AtomicInteger();
        try(var publisher=bus(ds,"publisher");var first=bus(ds,"first")) {
            var initial=first.subscribe(SubscriptionSpec.shared("recover-v1","device/**"),e->CompletableFuture.completedFuture(null));
            publisher.start();first.start();initial.ready().toCompletableFuture().get(15,TimeUnit.SECONDS);
            first.close();
            publisher.publish(event("offline","t1","d1")).toCompletableFuture().join();
            assertEquals(1,publisher.pendingCount());
            try(var replacement=bus(ds,"replacement")) {
                var ready=replacement.subscribe(SubscriptionSpec.shared("recover-v1","device/**"),e->{received.incrementAndGet();return CompletableFuture.completedFuture(null);});
                replacement.start();ready.ready().toCompletableFuture().get(15,TimeUnit.SECONDS);
                await(()->received.get()==1 && publisher.pendingCount()==0);
            }
        }
    }
    @Test void sharedReceiversCompeteAndBroadcastReceiversEachGetOneCopy() throws Exception {
        var ds=database();AtomicInteger shared=new AtomicInteger(),broadcast=new AtomicInteger();
        try(var publisher=bus(ds,"publisher");var first=bus(ds,"first");var second=bus(ds,"second")) {
            var a=first.subscribe(SubscriptionSpec.shared("shared","device/**"),e->{shared.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var b=second.subscribe(SubscriptionSpec.shared("shared","device/**"),e->{shared.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var c=first.subscribe(SubscriptionSpec.broadcast("broadcast","device/**"),e->{broadcast.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var d=second.subscribe(SubscriptionSpec.broadcast("broadcast","device/**"),e->{broadcast.incrementAndGet();return CompletableFuture.completedFuture(null);});
            publisher.start();first.start();second.start();
            CompletableFuture.allOf(a.ready().toCompletableFuture(),b.ready().toCompletableFuture(),c.ready().toCompletableFuture(),d.ready().toCompletableFuture()).get(15,TimeUnit.SECONDS);
            assertEquals(3,publisher.publish(event("fanout","t1","d1")).toCompletableFuture().join().subscriberCount());
            await(()->publisher.pendingCount()==0);
            assertEquals(1,shared.get());assertEquals(2,broadcast.get());
        }
    }
    @Test void handlerFailureRetriesAndRpcRejectsWrongCredential() throws Exception {
        var ds=database();AtomicInteger attempts=new AtomicInteger();
        try(var publisher=bus(ds,"publisher");var receiver=bus(ds,"receiver")) {
            var ready=receiver.subscribe(SubscriptionSpec.shared("retry","device/**"),e->attempts.incrementAndGet()==1?CompletableFuture.failedFuture(new IllegalStateException("transient")):CompletableFuture.completedFuture(null));
            publisher.start();receiver.start();ready.ready().toCompletableFuture().get(15,TimeUnit.SECONDS);
            publisher.publish(event("retry","t1","d1")).toCompletableFuture().join();
            await(()->attempts.get()==2 && publisher.pendingCount()==0);
        }
        int port=port();
        try(var receiver=new DubboEventBus(routes(ds),"test","auth-receiver","127.0.0.1",port,TOKEN,Duration.ofSeconds(2),1000);
                var transport=new DubboEventTransport("test",1000)) {
            receiver.start();transport.start("127.0.0.1",port(),(ns,tokens,ids,routes,bytes)->ids);
            assertThrows(Exception.class,()->transport.send("127.0.0.1:"+port,new String[]{"wrong"},new String[]{"id"},new String[]{"route"},new byte[][]{new byte[]{1}}));
            assertEquals(0,receiver.receivedRpcCount());
            assertThrows(Exception.class,()->transport.inspect("127.0.0.1:"+port,Long.toString(System.currentTimeMillis()),"wrong"));
        }
    }
    @Test void noKafkaAndRealCrossJvmDubboDelivery() throws Exception {
        var server=org.h2.tools.Server.createTcpServer("-tcpPort","0","-ifNotExists").start();
        Process process=null;
        try {
            String name="fork"+UUID.randomUUID().toString().replace("-","");
            var ds=new JdbcDataSource();ds.setURL("jdbc:h2:tcp://localhost:"+server.getPort()+"/mem:"+name+";DB_CLOSE_DELAY=-1");ds.setUser("sa");
            new ResourceDatabasePopulator(new ClassPathResource("db/message/V26__dubbo_event_routing.sql"),new ClassPathResource("db/message/V27__dynamic_route_lifecycle.sql"),new ClassPathResource("db/message/V28__event_source_scope.sql")).execute(ds);
            AtomicInteger count=new AtomicInteger();
            try(var receiver=bus(ds,"receiver")) {
                receiver.subscribe(SubscriptionSpec.shared("forked","device/**"),e->{count.incrementAndGet();return CompletableFuture.completedFuture(null);});receiver.start();
                Path output=Path.of("target","forked-dubbo.log").toAbsolutePath();
                process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ForkedDubboPublisher.class.getName(),ds.getURL(),Integer.toString(port())).redirectErrorStream(true).redirectOutput(output.toFile()).start();
                assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Forked publisher timeout");
                assertEquals(0,process.exitValue(),"See target/forked-dubbo.log");
                await(()->count.get()==1);assertTrue(receiver.receivedRpcCount()>0);
            }
        }finally{if(process!=null && process.isAlive())process.destroyForcibly();server.stop();}
    }
    @Test void topicIndexSupportsZeroSegmentRemainderAndDeduplicatesOverlaps() {
        var index=new TopicIndex<String>();index.add("device/**","same");index.add("device/*/property/report","same");index.add("other/*","other");
        assertEquals(Set.of("same"),index.match("device"));assertEquals(Set.of("same"),index.match("device/d1/property/report"));assertTrue(index.match("nothing/here").isEmpty());
    }
    @Test void durableBacklogSurvivesPublisherExitAndDeliveryCapacityIsAtomic() throws Exception {
        var ds=database();var store=routes(ds);store.initialize();
        String route=store.register("offline",new JdbcEventRoutes.Definition(SubscriptionSpec.shared("recover-source","device/**"),EventSelector.all()));
        String extra=store.register("offline",new JdbcEventRoutes.Definition(SubscriptionSpec.shared("overflow-target","device/**"),EventSelector.all()));
        store.join("old-source","127.0.0.1:1");
        var codec=new com.nebula.iot.message.kafka.EventJsonCodec();
        store.enqueue("old-source","event-1",codec.encode(event("source-crash","t1","d1")),Set.of(route),1);
        assertThrows(IllegalStateException.class,()->store.enqueue("old-source","event-2",codec.encode(event("overflow","t1","d1")),Set.of(route,extra),1));
        assertEquals(1,store.pending());
        assertTrue(store.claim("PENDING",Set.of(route),"other-source",Duration.ofSeconds(3),1).isEmpty());
        // Simulate an ungraceful death by expiring its directory lease, not by close().
        new JdbcTemplate(ds).update("UPDATE event_route_node SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE node_id='old-source'");
        assertThrows(IllegalStateException.class,()->store.heartbeat("old-source",0));
        AtomicInteger count=new AtomicInteger();
        try(var replacement=bus(ds,"replacement-source")) {
            var subscription=replacement.subscribe(SubscriptionSpec.shared("recover-source","device/**"),e->{count.incrementAndGet();return CompletableFuture.completedFuture(null);});
            replacement.start();subscription.ready().toCompletableFuture().get(15,TimeUnit.SECONDS);
            await(()->count.get()==1 && store.pending()==0);
        }
    }
    @Test void retirementCancelsBacklogAndCleanupDoesNotRetireAnUnstartedBroadcast() {
        var ds=database();var store=routes(ds);store.initialize();
        var shared=new JdbcEventRoutes.Definition(SubscriptionSpec.shared("retire","device/**"),EventSelector.all());
        String route=store.register("registered-not-started",shared);
        assertThrows(IllegalStateException.class,()->store.retireShared("retire"));
        var codec=new com.nebula.iot.message.kafka.EventJsonCodec();
        store.enqueue("source","old",codec.encode(event("old","t1","d1")),Set.of(route),10);
        store.detach("registered-not-started",route);store.retireShared("retire");
        assertEquals(0,store.pending());
        assertEquals("CANCELLED",new JdbcTemplate(ds).queryForObject("SELECT delivery_state FROM event_route_delivery",String.class));
        assertThrows(IllegalStateException.class,()->store.enqueue("source","new",codec.encode(event("new","t1","d1")),Set.of(route),10));
        String broadcast=store.register("not-started",new JdbcEventRoutes.Definition(SubscriptionSpec.broadcast("realtime","device/**"),EventSelector.all()));
        store.cleanup(Duration.ofDays(7));
        assertTrue(store.snapshot(-1).routes().stream().anyMatch(r->r.id().equals(broadcast)));
        store.join("not-started","127.0.0.1:1");store.leave("not-started");store.cleanup(Duration.ofDays(7));
        assertFalse(store.snapshot(-1).routes().stream().anyMatch(r->r.id().equals(broadcast)));
    }
    @Test void mysqlSchemaAndUtcLeaseValuesWorkInTheDialectAdapter() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");ds.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("db/message-mysql/V26__dubbo_event_routing.sql"),new ClassPathResource("db/message-mysql/V27__dynamic_route_lifecycle.sql"),new ClassPathResource("db/message-mysql/V28__event_source_scope.sql")).execute(ds);
        var store=routes(ds);store.initialize();store.join("mysql-adapter","127.0.0.1:1");store.heartbeat("mysql-adapter",0);
        assertEquals(1,store.snapshot(-1).nodes().size());
    }

    @Test void nodeMustBeReadyBeforeRoutingAndDrainRemovesItFromTheReadySet() {
        var ds=database();var store=routes(ds);store.initialize();
        store.join("lifecycle","127.0.0.1:1");
        assertFalse(store.ready("lifecycle"));
        store.markReady("lifecycle",0);
        assertTrue(store.ready("lifecycle"));
        store.beginDrain("lifecycle",Duration.ofSeconds(10));
        assertFalse(store.ready("lifecycle"));
    }

    @Test void staleClaimCannotCompleteAfterLeaseOwnershipChanges() {
        var ds=database();var store=routes(ds);store.initialize();
        store.join("owner-a","127.0.0.1:1");store.markReady("owner-a",0);
        String route=store.register("owner-a",new JdbcEventRoutes.Definition(SubscriptionSpec.shared("fenced","device/**"),EventSelector.all()));
        var codec=new com.nebula.iot.message.kafka.EventJsonCodec();
        store.enqueue("source","fenced-event",codec.encode(event("fenced-event","t1","d1")),Set.of(route),10);
        new JdbcTemplate(ds).update("UPDATE event_route_delivery SET available_at=TIMESTAMP '2000-01-01 00:00:00'");
        var first=store.claim("PENDING",Set.of(route),"owner-a",Duration.ofSeconds(1),1).getFirst();
        store.accept(first.id(),route,codec.encode(event("fenced-event","t1","d1")));
        new JdbcTemplate(ds).update("UPDATE event_route_delivery SET available_at=TIMESTAMP '2000-01-01 00:00:00'");
        first=store.claim("RECEIVED",Set.of(route),"owner-a",Duration.ofSeconds(30),1).getFirst();
        new JdbcTemplate(ds).update("UPDATE event_route_delivery SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE delivery_id=?",first.id());
        var second=store.claim("RECEIVED",Set.of(route),"owner-b",Duration.ofSeconds(30),1).getFirst();
        store.complete(first.id(),"owner-a",first.ownerToken());
        store.retry(first.id(),"owner-a",first.ownerToken());
        assertEquals("RECEIVED",new JdbcTemplate(ds).queryForObject("SELECT delivery_state FROM event_route_delivery WHERE delivery_id=?",String.class,first.id()));
        assertEquals(second.ownerToken(),new JdbcTemplate(ds).queryForObject("SELECT owner_token FROM event_route_delivery WHERE delivery_id=?",String.class,first.id()));
        store.complete(second.id(),"owner-b",second.ownerToken());
        assertEquals("DONE",new JdbcTemplate(ds).queryForObject("SELECT delivery_state FROM event_route_delivery WHERE delivery_id=?",String.class,first.id()));
    }

    @Test void reenabledLogicalSubscriptionGetsANewGeneration() {
        var ds=database();var store=routes(ds);store.initialize();
        var definition=new JdbcEventRoutes.Definition(SubscriptionSpec.shared("generation","device/**"),EventSelector.all());
        String first=store.register("generation-node",definition);
        store.detach("generation-node",first);store.retireShared("generation");
        String second=store.register("generation-node",definition);
        assertNotEquals(first,second);
    }

    @Test void managementDirectoryPaginatesAndSeparatesBacklogFromRuntimeMetrics() {
        var ds=database();var store=routes(ds);store.initialize();
        store.join("online","127.0.0.1:1");store.markReady("online",store.revision());
        store.join("offline","127.0.0.1:2");store.leave("offline");
        for(int i=0;i<21;i++)store.register("online",new JdbcEventRoutes.Definition(SubscriptionSpec.shared("rule-"+i,"device/**"),new EventSelector("tenant-a",Set.of("device-a"))));
        var overview=store.overview();
        assertEquals(1,overview.totalNodes());assertEquals(1,overview.activeNodes());assertEquals(21,overview.subscriptions());
        assertEquals(0,overview.backlog().pending());assertNull(overview.backlog().oldest());
        assertEquals(20,store.subscriptions(0).items().size());assertEquals(1,store.subscriptions(1).items().size());
        var sub=store.subscriptions(0).items().getFirst();
        assertEquals(List.of("online"),sub.members());assertEquals(1,sub.deviceCount());
        store.enqueue("source","pending","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),Set.of(sub.id()),10);
        assertEquals(1,store.subscriptionBacklog(sub.id()).pending());
        assertNotNull(store.subscriptionBacklog(sub.id()).oldest());
    }

    @Test void monitoringExcludesRestartHistoryAndUsesCurrentDiscoveryBeforeLimiting() {
        var ds=database();var store=routes(ds);store.initialize();
        var jdbc=new JdbcTemplate(ds);
        store.join("expired","127.0.0.1:1");
        jdbc.update("UPDATE event_route_node SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE node_id='expired'");
        store.join("exited","127.0.0.1:2");store.leave("exited");
        for(int i=0;i<105;i++)store.join("undiscovered-"+i,"127.0.0.1:3");
        store.join("replacement","127.0.0.1:4");
        var current=store.overview(Set.of("replacement","expired","exited"));
        assertEquals(1,current.totalNodes());assertEquals(1,current.activeNodes());
        assertEquals(List.of("replacement"),current.nodes().stream().map(JdbcEventRoutes.ObservedNode::id).toList());
        assertEquals(0,store.overview(Set.of()).totalNodes());
        assertTrue(store.overview(Set.of()).nodes().isEmpty());
        var legacy=store.overview();
        assertEquals(106,legacy.totalNodes());assertEquals(100,legacy.nodes().size());
        // Observation must preserve the lifecycle records used by delivery recovery.
        assertEquals(108,jdbc.queryForObject("SELECT COUNT(*) FROM event_route_node",Integer.class));
    }

    @Test void runtimeSubscriptionCanStopAndDrainWaitsForAnActiveHandler() throws Exception {
        var ds=database();var entered=new CompletableFuture<Void>();var release=new CompletableFuture<Void>();
        try(var publisher=bus(ds,"dynamic-publisher");var receiver=bus(ds,"dynamic-receiver")) {
            publisher.start();receiver.start();
            var subscription=receiver.subscribe(SubscriptionSpec.shared("dynamic","device/**"),e->{entered.complete(null);return release;});
            subscription.ready().toCompletableFuture().get(15,TimeUnit.SECONDS);
            publisher.publish(event("during-drain","t1","d1")).toCompletableFuture().join();
            entered.get(10,TimeUnit.SECONDS);
            var drained=receiver.drain(Duration.ofSeconds(10)).toCompletableFuture();
            assertFalse(receiver.healthy());assertFalse(drained.isDone());
            assertThrows(CompletionException.class,()->receiver.publish(event("rejected","t1","d1")).toCompletableFuture().join());
            release.complete(null);
            drained.get(15,TimeUnit.SECONDS);
            assertFalse(receiver.isRunning());
            await(()->publisher.pendingCount()==0);
            subscription.close();
            routes(ds).retireShared("dynamic");
            var replacement=publisher.subscribe(SubscriptionSpec.shared("dynamic","device/**"),e->CompletableFuture.completedFuture(null));
            replacement.ready().toCompletableFuture().get(15,TimeUnit.SECONDS);
            publisher.publish(event("after-drain","t1","d1")).toCompletableFuture().join();
            await(()->publisher.pendingCount()==0);
            replacement.close();routes(ds).retireShared("dynamic");
        } finally {release.complete(null);}
    }
}

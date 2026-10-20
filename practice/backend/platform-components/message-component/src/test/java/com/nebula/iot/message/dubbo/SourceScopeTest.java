package com.nebula.iot.message.dubbo;

import com.nebula.iot.message.*;
import com.nebula.iot.message.routing.EventSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nebula.iot.message.dubbo.DubboEventBusTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceScopeTest {
    @Test void oldDefinitionsDefaultToAllAndLocalBusNeverInvokesRemoteOnly() throws Exception {
        var old=new ObjectMapper().readValue("{\"name\":\"old\",\"patterns\":[\"device/**\"],\"mode\":\"SHARED\"}",SubscriptionSpec.class);
        assertEquals(SubscriptionSpec.SourceScope.ALL,old.sourceScope());
        var bus=new LocalEventBus();var count=new AtomicInteger();
        bus.subscribe(old.withSourceScope(SubscriptionSpec.SourceScope.REMOTE),e->{fail("Local events are not remote");return CompletableFuture.completedFuture(null);});
        bus.subscribe(old.withSourceScope(SubscriptionSpec.SourceScope.LOCAL),e->{count.incrementAndGet();return CompletableFuture.completedFuture(null);});
        assertEquals(1,bus.publish(event("one","t","d")).toCompletableFuture().join().subscriberCount());
        assertEquals(1,count.get());
    }
    @Test void twoProcessesRouteLocalRemoteAndAllWithoutChangingSharedSemantics() throws Exception {
        var ds=database();var localA=new AtomicInteger();var localB=new AtomicInteger();
        var remoteA=new AtomicInteger();var remoteB=new AtomicInteger();var all=new AtomicInteger();
        try(var a=bus(ds,"scope-a");var b=bus(ds,"scope-b")){
            var la=a.subscribe(SubscriptionSpec.shared("local-store","device/**").withSourceScope(SubscriptionSpec.SourceScope.LOCAL),e->{localA.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var lb=b.subscribe(SubscriptionSpec.shared("local-store","device/**").withSourceScope(SubscriptionSpec.SourceScope.LOCAL),e->{localB.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var ra=a.subscribe(SubscriptionSpec.shared("remote-worker","device/**").withSourceScope(SubscriptionSpec.SourceScope.REMOTE),e->{assertEquals("from-b",e.eventId());remoteA.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var rb=b.subscribe(SubscriptionSpec.shared("remote-worker","device/**").withSourceScope(SubscriptionSpec.SourceScope.REMOTE),e->{assertEquals("from-a",e.eventId());remoteB.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var aa=a.subscribe(SubscriptionSpec.shared("all-worker","device/**"),e->{all.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var ab=b.subscribe(SubscriptionSpec.shared("all-worker","device/**"),e->{all.incrementAndGet();return CompletableFuture.completedFuture(null);});
            a.start();b.start();
            CompletableFuture.allOf(la.ready().toCompletableFuture(),lb.ready().toCompletableFuture(),ra.ready().toCompletableFuture(),rb.ready().toCompletableFuture(),aa.ready().toCompletableFuture(),ab.ready().toCompletableFuture()).get(15,TimeUnit.SECONDS);
            assertEquals(3,a.publish(event("from-a","t","d")).toCompletableFuture().join().subscriberCount());
            assertEquals(3,b.publish(event("from-b","t","d")).toCompletableFuture().join().subscriberCount());
            await(()->a.pendingCount()==0);
            assertEquals(1,localA.get());assertEquals(1,localB.get());
            assertEquals(1,remoteA.get());assertEquals(1,remoteB.get());assertEquals(2,all.get());
        }
    }
    @Test void remoteBroadcastExcludesPublisherAndLocalBroadcastStaysLocal() throws Exception {
        var ds=database();var remote=new AtomicInteger();var local=new AtomicInteger();
        try(var a=bus(ds,"broadcast-a");var b=bus(ds,"broadcast-b")){
            var own=a.subscribe(SubscriptionSpec.broadcast("remote","device/**").withSourceScope(SubscriptionSpec.SourceScope.REMOTE),e->{fail("Publisher must not receive remote broadcast");return CompletableFuture.completedFuture(null);});
            var other=b.subscribe(SubscriptionSpec.broadcast("remote","device/**").withSourceScope(SubscriptionSpec.SourceScope.REMOTE),e->{remote.incrementAndGet();return CompletableFuture.completedFuture(null);});
            var localSub=a.subscribe(SubscriptionSpec.broadcast("local","device/**").withSourceScope(SubscriptionSpec.SourceScope.LOCAL),e->{local.incrementAndGet();return CompletableFuture.completedFuture(null);});
            a.start();b.start();
            CompletableFuture.allOf(own.ready().toCompletableFuture(),other.ready().toCompletableFuture(),localSub.ready().toCompletableFuture()).get(15,TimeUnit.SECONDS);
            assertEquals(2,a.publish(event("broadcast","t","d")).toCompletableFuture().join().subscriberCount());
            await(()->a.pendingCount()==0);
            assertEquals(1,remote.get());assertEquals(1,local.get());
        }
    }
    @Test void recoveryKeepsOriginalSourceAndReceiverClaimsCannotBypassScope() {
        var ds=database();var store=routes(ds);store.initialize();var jdbc=new JdbcTemplate(ds);
        var definition=new JdbcEventRoutes.Definition(SubscriptionSpec.shared("remote","device/**").withSourceScope(SubscriptionSpec.SourceScope.REMOTE),EventSelector.all());
        String route=store.register("origin",definition);store.register("relay",definition);
        store.join("origin","127.0.0.1:1");store.markReady("origin",store.revision());
        byte[] bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.enqueue("origin","recovery",bytes,Set.of(route),10);store.leave("origin");
        jdbc.update("UPDATE event_route_delivery SET available_at=TIMESTAMP '2000-01-01 00:00:00'");
        var recovered=store.claim("PENDING",Set.of(route),"relay",Duration.ofSeconds(30),1).getFirst();
        assertEquals("origin",recovered.sourceNode());
        assertThrows(IllegalStateException.class,()->store.accept(recovered.id(),route,bytes,"origin"));
        store.accept(recovered.id(),route,bytes,"relay");
        jdbc.update("UPDATE event_route_delivery SET available_at=TIMESTAMP '2000-01-01 00:00:00'");
        assertTrue(store.claim("RECEIVED",Set.of(route),"origin",Duration.ofSeconds(30),1).isEmpty());
        assertEquals(1,store.claim("RECEIVED",Set.of(route),"relay",Duration.ofSeconds(30),1).size());
    }
    @Test void localSubscriptionBacklogIsCancelledOnExitInsteadOfMovingToAnotherProcess() {
        var ds=database();var store=routes(ds);store.initialize();var jdbc=new JdbcTemplate(ds);
        var definition=new JdbcEventRoutes.Definition(SubscriptionSpec.shared("local","device/**").withSourceScope(SubscriptionSpec.SourceScope.LOCAL),EventSelector.all());
        String route=store.register("origin",definition),other=store.register("other",definition);
        assertNotEquals(route,other);store.join("origin","127.0.0.1:1");
        store.enqueue("origin","local",new byte[]{123,125},Set.of(route),10);
        store.leave("origin");store.cleanup(Duration.ofDays(7));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT delivery_state FROM event_route_delivery",String.class));
        assertEquals(0,store.pending());
    }
}

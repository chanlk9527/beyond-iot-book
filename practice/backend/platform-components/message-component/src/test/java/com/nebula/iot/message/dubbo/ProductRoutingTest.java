package com.nebula.iot.message.dubbo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.message.*;
import com.nebula.iot.message.kafka.EventJsonCodec;
import com.nebula.iot.message.routing.EventSelector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.nebula.iot.message.dubbo.DubboEventBusTest.*;

class ProductRoutingTest {
    static EventMessage productEvent(String id, String tenant, String product, String device) {
        var now = Instant.now();
        return new EventMessage(id, "device/" + device + "/property/report", now, now,
                tenant, device, "v1", id, "DEVICE", Map.of(), Map.of("temperature", 70), product);
    }

    @Test void productSelectionIntersectsTenantAndDevicesAndRejectsMissingTenant() throws Exception {
        var selector = EventSelector.product("t1", "p1");
        assertTrue(selector.matches(productEvent("a", "t1", "p1", "new-device")));
        assertFalse(selector.matches(productEvent("b", "t1", "p2", "new-device")));
        assertFalse(selector.matches(productEvent("c", "t2", "p1", "new-device")));
        assertFalse(selector.matches(event("legacy", "t1", "new-device")));
        var selectedDevice = new EventSelector("t1", "p1", Set.of("d1"));
        assertTrue(selectedDevice.matches(productEvent("d", "t1", "p1", "d1")));
        assertFalse(selectedDevice.matches(productEvent("e", "t1", "p2", "d1")));
        assertFalse(selectedDevice.matches(productEvent("f", "t1", "p1", "d2")));
        assertThrows(IllegalArgumentException.class, () -> new EventSelector(null, "p1", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> EventSelector.product("t1", " "));
        var json = new ObjectMapper();
        assertEquals(new EventSelector("t1", Set.of("d1")),
                json.readValue("{\"tenantId\":\"t1\",\"deviceIds\":[\"d1\"]}", EventSelector.class));
        assertEquals(selector, json.readValue(json.writeValueAsString(selector), EventSelector.class));
    }

    @Test void codecPreservesProductAndReadsOldEnvelopes() {
        var codec = new EventJsonCodec();
        var selected = productEvent("selected", "t1", "p1", "d1");
        assertEquals(selected, codec.decode(codec.encode(selected)));
        var legacy = event("old", "t1", "d1");
        assertEquals(legacy, codec.decode(codec.encode(legacy)));
        assertFalse(new String(codec.encode(legacy), StandardCharsets.UTF_8).contains("productId"));
    }

    @Test void localBusFiltersProductsAndInvokesOverlappingPatternsOnlyOnce() {
        var bus = new LocalEventBus(Runnable::run, Duration.ofSeconds(1));
        var received = new ArrayList<String>();
        try (var sub = bus.subscribe(SubscriptionSpec.shared("product", "device/**", "device/*/property/report"),
                EventSelector.product("t1", "p1"), e -> {
                    received.add(e.deviceId()); return CompletableFuture.completedFuture(null);
                })) {
            bus.publish(productEvent("a", "t1", "p1", "d1")).toCompletableFuture().join();
            bus.publish(productEvent("b", "t1", "p2", "d2")).toCompletableFuture().join();
            bus.publish(productEvent("c", "t2", "p1", "d3")).toCompletableFuture().join();
            bus.publish(event("legacy", "t1", "d4")).toCompletableFuture().join();
            bus.publish(productEvent("new", "t1", "p1", "new-device")).toCompletableFuture().join();
            assertEquals(List.of("d1", "new-device"), received);
        }
    }

    @Test void dubboIndexesProductsWithoutEnumeratingDevicesAndPersistsTheirScope() throws Exception {
        var ds = database();
        var jdbc = new JdbcTemplate(ds);
        var received = new CopyOnWriteArrayList<String>();
        try (var publisher = bus(ds, "product-publisher"); var receiver = bus(ds, "product-receiver")) {
            var spec = SubscriptionSpec.shared("product-p1", "device/**", "device/*/property/report");
            var selector = EventSelector.product("t1", "p1");
            var selected = receiver.subscribe(spec, selector, e -> {
                received.add(e.deviceId()); assertEquals("p1", e.productId());
                return CompletableFuture.completedFuture(null);
            });
            publisher.start(); receiver.start(); selected.ready().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(0, publisher.publish(productEvent("other-product", "t1", "p2", "d1")).toCompletableFuture().join().subscriberCount());
            assertEquals(0, publisher.publish(productEvent("other-tenant", "t2", "p1", "d1")).toCompletableFuture().join().subscriberCount());
            assertEquals(0, publisher.publish(event("missing-product", "t1", "d1")).toCompletableFuture().join().subscriberCount());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM event_route_delivery", Integer.class));
            assertEquals(0, receiver.receivedRpcCount());
            assertEquals(1, publisher.publish(productEvent("first", "t1", "p1", "d1")).toCompletableFuture().join().subscriberCount());
            assertEquals(1, publisher.publish(productEvent("new", "t1", "p1", "new-device")).toCompletableFuture().join().subscriberCount());
            await(() -> received.size() == 2 && publisher.pendingCount() == 0);
            assertEquals(Set.of("d1", "new-device"), Set.copyOf(received));
            var view = routes(ds).subscriptions(0).items().getFirst();
            assertEquals("t1", view.tenantId()); assertEquals("p1", view.productId()); assertEquals(0, view.deviceCount());
            assertThrows(IllegalStateException.class, () -> routes(ds).register("different-member",
                    new JdbcEventRoutes.Definition(spec, EventSelector.product("t1", "p2"))));
            selected.close();
            publisher.publish(productEvent("offline", "t1", "p1", "d3")).toCompletableFuture().join();
            assertEquals(1, publisher.pendingCount());
            var resumed = receiver.subscribe(spec, selector, e -> {
                received.add(e.deviceId()); return CompletableFuture.completedFuture(null);
            });
            resumed.ready().toCompletableFuture().get(15, TimeUnit.SECONDS);
            await(() -> received.size() == 3 && publisher.pendingCount() == 0);
            assertTrue(received.contains("d3"));
        }
    }
}

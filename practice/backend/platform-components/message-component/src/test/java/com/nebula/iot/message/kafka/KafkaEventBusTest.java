package com.nebula.iot.message.kafka;

import com.nebula.iot.core.message.DeviceMessageBody;
import com.nebula.iot.message.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import static org.junit.jupiter.api.Assertions.*;

class KafkaEventBusTest {
    private static final String TOPIC = "eventbus-test";
    private static final String FAILED = "eventbus-test-failed";
    private static EmbeddedKafkaKraftBroker broker;

    @BeforeAll static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 3, TOPIC, FAILED);
        broker.afterPropertiesSet();
    }
    @AfterAll static void stopBroker() { if (broker != null) broker.destroy(); }

    private KafkaEventBus bus(String node) {
        var config = Map.<String, Object>of("bootstrap.servers", broker.getBrokersAsString());
        return new KafkaEventBus(TOPIC, FAILED, "test", node, config, config, Duration.ofSeconds(2));
    }
    @Test void sourceScopesAreRejectedInsteadOfSilentlyFilteringSharedGroupRecords() {
        try(var receiver=bus("scope-validation")){
            for(var scope:List.of(SubscriptionSpec.SourceScope.LOCAL,SubscriptionSpec.SourceScope.REMOTE))
                assertThrows(IllegalArgumentException.class,()->receiver.subscribe(
                        SubscriptionSpec.shared("scoped","device/**").withSourceScope(scope),
                        event->CompletableFuture.completedFuture(null)));
        }
    }

    @Test void productSubscriptionsFilterTenantAndProductWithoutEnumeratingDevices() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var receiver=bus("product-receiver"); var sender=bus("product-sender")) {
            receiver.subscribe(SubscriptionSpec.shared("product-p1", "product-filter/**"),
                    com.nebula.iot.message.routing.EventSelector.product("t1", "p1"), event -> {
                        received.add((String)((Map<?,?>)event.payload()).get("device")); return CompletableFuture.completedFuture(null);
                    });
            await(() -> ready(receiver));
            var now = java.time.Instant.now();
            String[][] cases = {{"t1","p2","ignored-product"},{"t2","p1","ignored-tenant"},{"t1",null,"legacy"},{"t1","p1","d1"},{"t1","p1","new-device"}};
            for (var item : cases) {
                String id=java.util.UUID.randomUUID().toString();
                sender.publish(new EventMessage(id, "product-filter/report", now, now, item[0], "partition-device", "v1", id,
                        "DEVICE", Map.of(), Map.of("device",item[2]), item[1])).toCompletableFuture().get(10,TimeUnit.SECONDS);
            }
            await(() -> received.size()==2);
            assertEquals(java.util.List.of("d1","new-device"),received);
        }
    }

    @Test void deferredLifecycleDoesNotConsumeBacklogBeforeDomainListenersAreReady() throws Exception {
        var received=new CompletableFuture<EventMessage>();
        try(var receiver=bus("lifecycle").deferSubscriptionsUntilStart();var sender=bus("lifecycle-sender")) {
            receiver.subscribe(SubscriptionSpec.shared("lifecycle-test","lifecycle/**"),event->{
                received.complete(event);return CompletableFuture.completedFuture(null);
            });
            sender.publish("lifecycle/report",Map.of("ready",true)).toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertFalse(receiver.isRunning());
            assertTrue(receiver.assignedPartitions().values().stream().allMatch(n->n==0));
            assertFalse(received.isDone());
            receiver.start();
            assertEquals("lifecycle/report",received.get(20,TimeUnit.SECONDS).topic());
            receiver.stop();
            assertFalse(receiver.isRunning());
        }
    }

    @Test void sharedGroupsBroadcastAndLogicalPatterns() throws Exception {
        var counts = new ConcurrentHashMap<String, AtomicInteger>();
        var a = new AtomicInteger(); var b = new AtomicInteger();
        var handledA = new AtomicInteger(); var handledB = new AtomicInteger();
        try (var first = bus("node-a"); var second = bus("node-b")) {
            EventSubscriber shared = event -> {
                counts.computeIfAbsent(event.eventId(), key -> new AtomicInteger()).incrementAndGet();
                return CompletableFuture.completedFuture(null);
            };
            first.subscribe(SubscriptionSpec.shared("shadow", "shared/device/*/property/report"), event -> {
                handledA.incrementAndGet(); return shared.onEvent(event);
            });
            second.subscribe(SubscriptionSpec.shared("shadow", "shared/device/*/property/report"), event -> {
                handledB.incrementAndGet(); return shared.onEvent(event);
            });
            first.subscribe(SubscriptionSpec.broadcast("realtime", "shared/device/d001/**", "shared/device/d002/**"), event -> {
                a.incrementAndGet(); return CompletableFuture.completedFuture(null);
            });
            second.subscribe(SubscriptionSpec.broadcast("realtime", "shared/device/d001/**", "shared/device/d002/**"), event -> {
                b.incrementAndGet(); return CompletableFuture.completedFuture(null);
            });
            await(() -> ready(first) && ready(second));
            for (int i = 1; i <= 24; i++) {
                String device = String.format("d%03d", i);
                String id = UUID.randomUUID().toString(); var now = java.time.Instant.now();
                var event = new EventMessage(id, "shared/device/" + device + "/property/report", now, now,
                        "tenant", device, "v1", id, "APPLICATION", Map.of(), Map.of("temperature", 40));
                var result = first.publish(event).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(PublishResult.Confirmation.BROKER_ACCEPTED, result.confirmation());
                assertEquals(-1, result.subscriberCount());
            }
            await(() -> counts.size() == 24 && a.get() == 2 && b.get() == 2);
            assertTrue(counts.values().stream().allMatch(n -> n.get() == 1));
            assertTrue(handledA.get() > 0 && handledB.get() > 0, "Both shared instances must handle partitions");
            assertThrows(IllegalArgumentException.class, () -> first.subscribe("device/**", shared));
            assertThrows(IllegalArgumentException.class, () -> first.subscribe(SubscriptionSpec.shared("shadow", "device/**"), shared));
        }
    }

    @Test void sharedSubscriptionResumesAfterDisconnectAndConsumesOfflineBacklog() throws Exception {
        var firstDelivery = new CompletableFuture<Void>();
        try (var sender = bus("recovery-sender")) {
            try (var first = bus("recovery-a")) {
                first.subscribe(SubscriptionSpec.shared("recovery", "recovery/**"), event -> {
                    firstDelivery.complete(null); return CompletableFuture.completedFuture(null);
                });
                await(() -> ready(first));
                sender.publish("recovery/first", Map.of()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                firstDelivery.get(10, TimeUnit.SECONDS);
            }
            sender.publish("recovery/offline", Map.of()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var received = new CompletableFuture<String>();
            try (var replacement = bus("recovery-b")) {
                replacement.subscribe(SubscriptionSpec.shared("recovery", "recovery/**"), event -> {
                    received.complete(event.topic()); return CompletableFuture.completedFuture(null);
                });
                assertEquals("recovery/offline", received.get(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test void receivesTypedEventFromSeparateJvm() throws Exception {
        var received = new CompletableFuture<EventMessage>();
        try (var receiver = bus("fork-receiver")) {
            receiver.subscribe(SubscriptionSpec.broadcast("forked", "device/d001/**"), event -> {
                received.complete(event); return CompletableFuture.completedFuture(null);
            });
            await(() -> ready(receiver));
            String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            if (!System.getProperty("os.name").startsWith("Windows"))
                java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var process = new ProcessBuilder(java, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    ForkedEventPublisher.class.getName(), broker.getBrokersAsString(), TOPIC, FAILED).inheritIO().start();
            try {
                assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Publisher process timed out");
                assertEquals(0, process.exitValue());
                assertInstanceOf(DeviceMessageBody.PropertyReport.class, received.get(20, TimeUnit.SECONDS).payload());
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }

    @Test void retriesThenRetainsOriginalRecordInFailureTopicAndContinues() throws Exception {
        var attempts = new AtomicInteger();
        var completed = new CompletableFuture<Void>();
        String id = UUID.randomUUID().toString();
        try (var receiver = bus("failure-node")) {
            receiver.subscribe(SubscriptionSpec.shared("failure-test", "failure/**"), event -> {
                if (event.eventId().equals(id)) {
                    attempts.incrementAndGet();
                    return CompletableFuture.failedFuture(new IllegalStateException("test failure"));
                }
                completed.complete(null); return CompletableFuture.completedFuture(null);
            });
            await(() -> ready(receiver));
            var now = java.time.Instant.now();
            var bad = new EventMessage(id, "failure/test", now, now, "t", "d", "v1", id, "APPLICATION", Map.of(), Map.of("bad", true));
            receiver.publish(bad).toCompletableFuture().get(10, TimeUnit.SECONDS);
            receiver.publish(new EventMessage(id + "-ok", "failure/test", now, now, "t", "d", "v1", id + "-ok", "APPLICATION", Map.of(), Map.of())).toCompletableFuture().get(10, TimeUnit.SECONDS);
            completed.get(30, TimeUnit.SECONDS);
            assertEquals(4, attempts.get());
            var config = new HashMap<String, Object>();
            config.put("bootstrap.servers", broker.getBrokersAsString());
            config.put("group.id", "failure-reader-" + id);
            config.put("auto.offset.reset", "earliest");
            try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
                consumer.subscribe(List.of(FAILED));
                var found = new AtomicInteger();
                var codec = new EventJsonCodec();
                await(() -> {
                    consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                        if (codec.decode(record.value()).eventId().equals(id)) found.incrementAndGet();
                    });
                    return found.get() > 0;
                });
            }
        }
    }

    private static boolean ready(KafkaEventBus bus) {
        return !bus.assignedPartitions().isEmpty() && bus.assignedPartitions().values().stream().allMatch(n -> n > 0);
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for Kafka delivery/assignment");
            Thread.sleep(50);
        }
    }
}

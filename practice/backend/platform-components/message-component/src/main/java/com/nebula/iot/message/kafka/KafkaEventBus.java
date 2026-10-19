package com.nebula.iot.message.kafka;

import com.nebula.iot.message.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

/** One physical event topic; named service subscriptions filter logical topics in the consumer. */
public final class KafkaEventBus implements EventBus, AutoCloseable, org.springframework.context.SmartLifecycle {
    private final String topic;
    private final String deadLetterTopic;
    private final String namespace;
    private final String nodeId;
    private final Duration handlerTimeout;
    private final EventJsonCodec codec = new EventJsonCodec();
    private final DefaultKafkaProducerFactory<String, byte[]> producers;
    private final KafkaTemplate<String, byte[]> template;
    private final Map<String, Object> consumerProperties;
    private final Map<String, KafkaMessageListenerContainer<String, byte[]>> subscriptions = new HashMap<>();
    private final Map<String, ExecutorService> handlers = new HashMap<>();
    private boolean closed;
    private boolean running = true;

    /** Boot must finish registering domain event listeners before consuming retained records. */
    public synchronized KafkaEventBus deferSubscriptionsUntilStart() {
        if (!subscriptions.isEmpty()) throw new IllegalStateException("Configure lifecycle before subscribing");
        running = false;
        return this;
    }

    @Override public int getPhase() { return 50; }
    @Override public synchronized boolean isRunning() { return running && !closed; }
    @Override public synchronized void start() {
        ensureOpen();
        if (running) return;
        running = true;
        try { subscriptions.values().forEach(KafkaMessageListenerContainer::start); }
        catch (RuntimeException error) { running = false; subscriptions.values().forEach(KafkaMessageListenerContainer::stop); throw error; }
    }
    @Override public void stop() {
        List<KafkaMessageListenerContainer<String, byte[]>> active;
        synchronized (this) { running = false; active = List.copyOf(subscriptions.values()); }
        active.forEach(KafkaMessageListenerContainer::stop);
    }

    public KafkaEventBus(String topic, String deadLetterTopic, String namespace, String nodeId,
                         Map<String, Object> producerProperties, Map<String, Object> consumerProperties,
                         Duration handlerTimeout) {
        this.topic = requireName(topic);
        this.deadLetterTopic = requireName(deadLetterTopic);
        if (topic.equals(deadLetterTopic)) throw new IllegalArgumentException("Failure topic must differ from event topic");
        this.namespace = requireName(namespace);
        this.nodeId = requireName(nodeId);
        if (handlerTimeout.isNegative() || handlerTimeout.isZero() || handlerTimeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Handler timeout must be between 0 and 60 seconds");
        this.handlerTimeout = handlerTimeout;
        var producerConfig = new HashMap<>(producerProperties);
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerConfig.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        producerConfig.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        producerConfig.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        producerConfig.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // Fail startup on inaccessible/missing infrastructure; never silently fall back to local delivery.
        try (var admin = AdminClient.create(producerConfig)) {
            admin.describeTopics(List.of(topic, deadLetterTopic)).allTopicNames().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka EventBus requires accessible, pre-created event and failure topics", e);
        }
        producers = new DefaultKafkaProducerFactory<>(producerConfig, new StringSerializer(), new ByteArraySerializer());
        template = new KafkaTemplate<>(producers);
        this.consumerProperties = new HashMap<>(consumerProperties);
        this.consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        this.consumerProperties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        this.consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        this.consumerProperties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
    }

    @Override public boolean supportsCluster() { return true; }

    @Override public Subscription subscribe(String pattern, EventSubscriber subscriber) {
        throw new IllegalArgumentException("Kafka subscriptions require a named SubscriptionSpec (service group or node broadcast)");
    }

    @Override public Subscription subscribe(String pattern, EventSubscriber subscriber, int priority) {
        throw new IllegalArgumentException("Kafka does not support cross-consumer priority; use a named SubscriptionSpec");
    }

    @Override public synchronized Subscription subscribe(SubscriptionSpec spec, EventSubscriber subscriber) {
        ensureOpen();
        Objects.requireNonNull(subscriber, "subscriber");
        if (subscriptions.containsKey(spec.name())) throw new IllegalArgumentException("Duplicate subscription name: " + spec.name());
        if (subscriptions.size() >= 64) throw new IllegalStateException("Service subscription limit reached; App subscriptions belong in the realtime gateway");
        String group = namespace + "." + spec.name()
                + (spec.mode() == SubscriptionSpec.Mode.BROADCAST ? ".node." + nodeId : "");
        var config = new HashMap<>(consumerProperties);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                spec.mode() == SubscriptionSpec.Mode.SHARED ? "earliest" : "latest");
        var factory = new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new ByteArrayDeserializer());
        var properties = new ContainerProperties(topic);
        // One bounded worker per service subscription. Include synchronous callback time in the timeout.
        var handler = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon(true).name("eventbus-handler-" + spec.name()).factory());
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMissingTopicsFatal(true);
        properties.setMessageListener((MessageListener<String, byte[]>) record -> {
            EventMessage event = codec.decode(record.value());
            if (spec.matches(event.topic())) {
                Future<?> work = null;
                try {
                    // Ordered per partition, bounded wait. A timeout does not cancel external business effects.
                    work = handler.submit(() -> {
                        try {
                            Objects.requireNonNull(subscriber.onEvent(event), "Subscriber result")
                                    .toCompletableFuture().get();
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                            throw new CompletionException(e);
                        }
                    });
                    work.get(handlerTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    if (work != null) work.cancel(true);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new IllegalStateException("Event subscriber failed: " + spec.name(), e);
                }
            }
        });
        var container = new KafkaMessageListenerContainer<>(factory, properties);
        container.setBeanName("eventbus-" + spec.name() + "-" + nodeId);
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(deadLetterTopic, -1));
        recoverer.setFailIfSendResultIsError(true);
        var errors = new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 3));
        // Failed records are skipped only after the failure publication is acknowledged.
        errors.setAckAfterHandle(true);
        container.setCommonErrorHandler(errors);
        subscriptions.put(spec.name(), container);
        handlers.put(spec.name(), handler);
        try { if (running) container.start(); }
        catch (RuntimeException e) {
            subscriptions.remove(spec.name()); handlers.remove(spec.name());
            handler.shutdownNow(); container.stop(); throw e;
        }
        return () -> unsubscribe(spec.name(), container);
    }

    private void unsubscribe(String name, KafkaMessageListenerContainer<String, byte[]> container) {
        ExecutorService handler;
        synchronized (this) {
            // An already closed handle must not remove a replacement subscription with the same name.
            if (!subscriptions.remove(name, container)) return;
            handler = handlers.remove(name);
        }
        container.stop();
        handler.shutdownNow();
    }

    /** Operational snapshot; zero assignments can also mean idle excess consumers in a shared group. */
    public synchronized Map<String, Integer> assignedPartitions() {
        var result = new HashMap<String, Integer>();
        subscriptions.forEach((name, container) -> result.put(name,
                container.getAssignedPartitions() == null ? 0 : container.getAssignedPartitions().size()));
        return Map.copyOf(result);
    }

    /** Broker acknowledgement only; subscriberCount=-1 means unknown, never zero subscribers. */
    @Override public synchronized CompletionStage<PublishResult> publish(EventMessage event) {
        ensureOpen();
        try {
            String key = partitionKey(event);
            return template.send(topic, key, codec.encode(event))
                    .thenApply(result -> new PublishResult(event.eventId(), event.topic(), -1,
                            PublishResult.Confirmation.BROKER_ACCEPTED));
        } catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
    }

    public static String partitionKey(EventMessage event) {
        return event.tenantId().length() + ":" + event.tenantId() + event.deviceId();
    }

    private static String requireName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_-][a-zA-Z0-9._-]{0,149}"))
            throw new IllegalArgumentException("Invalid Kafka EventBus name");
        return name;
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("EventBus closed"); }

    @Override public void close() {
        List<KafkaMessageListenerContainer<String, byte[]>> active;
        List<ExecutorService> workers;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = List.copyOf(subscriptions.values());
            workers = List.copyOf(handlers.values());
            subscriptions.clear();
            handlers.clear();
        }
        workers.forEach(ExecutorService::shutdownNow);
        active.forEach(KafkaMessageListenerContainer::stop);
        template.destroy();
        producers.destroy();
    }
}

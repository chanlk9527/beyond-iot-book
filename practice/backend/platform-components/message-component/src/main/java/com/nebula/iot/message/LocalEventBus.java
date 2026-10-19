package com.nebula.iot.message;

import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;

/** In-process bus: matched subscribers run concurrently on the supplied executor. */
public final class LocalEventBus implements EventBus {
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final Executor executor; private final Duration timeout;
    public LocalEventBus() { this(ForkJoinPool.commonPool(), Duration.ofSeconds(30)); }
    public LocalEventBus(Executor executor, Duration timeout) { this.executor=Objects.requireNonNull(executor); this.timeout=Objects.requireNonNull(timeout); if(timeout.isNegative()||timeout.isZero()) throw new IllegalArgumentException("timeout must be positive"); }
    @Override public Subscription subscribe(String pattern, EventSubscriber subscriber) { return subscribe(pattern, subscriber, 0); }
    @Override public Subscription subscribe(String pattern, EventSubscriber subscriber, int priority) {
        TopicPattern.validatePattern(pattern); Objects.requireNonNull(subscriber, "subscriber"); var e=new Entry(java.util.List.of(pattern),subscriber,priority); entries.add(e); return ()->entries.remove(e);
    }
    @Override public Subscription subscribe(SubscriptionSpec spec, EventSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        var entry = new Entry(spec.patterns(), subscriber, 0);
        entries.add(entry);
        return () -> entries.remove(entry);
    }
    @Override public CompletionStage<PublishResult> publish(EventMessage event) {
        Objects.requireNonNull(event, "event"); var matched=entries.stream().filter(e->e.patterns.stream().anyMatch(p -> TopicPattern.matches(p,event.topic()))).sorted(Comparator.comparingInt(Entry::priority).reversed()).toList();
        var calls=matched.stream().map(e->invoke(e,event)).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(calls).thenApply(x->new PublishResult(event.eventId(),event.topic(),matched.size()));
    }
    private CompletableFuture<Void> invoke(Entry e, EventMessage event) { return CompletableFuture.runAsync(()->{ try { var result=e.subscriber.onEvent(event); if(result==null) throw new IllegalStateException("subscriber returned null"); result.toCompletableFuture().orTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS).join(); } catch(CompletionException x){throw x;} catch(Throwable x){throw new CompletionException(x);} },executor); }
    private record Entry(java.util.List<String> patterns, EventSubscriber subscriber, int priority) { }
}

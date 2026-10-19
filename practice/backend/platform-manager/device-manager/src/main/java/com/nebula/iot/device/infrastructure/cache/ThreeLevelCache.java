package com.nebula.iot.device.infrastructure.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Generic cache-aside implementation: local Caffeine -> Redis -> authoritative loader. */
public final class ThreeLevelCache<K, V> {
    private final Cache<K, Entry<V>> local;
    private final String namespace;
    private final ObjectMapper json;
    private final JavaType valueType;
    private final StringRedisTemplate redis;
    private final Duration redisTtl;
    private final Function<K, String> keyEncoder;
    /*
     * A local hit used to perform a synchronous Redis GET for the generation on
     * every access. With a remote Redis this made the hot MQTT command path pay
     * one network round trip per cache lookup, even when the value was already
     * in Caffeine. Poll generation briefly; local writes still invalidate the
     * Caffeine entry immediately and cross-node invalidation is bounded by this
     * small interval.
     */
    private static final long GENERATION_CHECK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private volatile String generationSnapshot = "0";
    private volatile long generationCheckedAtNanos;
    private final LongAdder localHits = new LongAdder();
    private final LongAdder redisHits = new LongAdder();
    private final LongAdder sourceLoads = new LongAdder();

    public ThreeLevelCache(String namespace, ObjectMapper json, JavaType valueType,
            StringRedisTemplate redis, int maximumSize, Duration localTtl, Duration redisTtl,
            Function<K, String> keyEncoder) {
        this.namespace = namespace;
        this.json = json;
        this.valueType = valueType;
        this.redis = redis;
        this.redisTtl = redisTtl;
        this.keyEncoder = keyEncoder;
        this.local = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterAccess(localTtl).build();
    }

    public V get(K key, Supplier<V> source) {
        var cached = local.getIfPresent(key);
        if (cached != null && current(cached.generation())) { localHits.increment(); return cached.value(); }
        if (cached != null) local.invalidate(key);
        return local.get(key, ignored -> load(key, source)).value();
    }

    public void put(K key, V value) {
        if (value == null) { evict(key); return; }
        var generation = generation();
        local.put(key, new Entry<>(value, generation));
        writeRedis(key, value, generation);
    }

    public void evict(K key) {
        local.invalidate(key);
        if (redis != null) try { redis.delete(redisKey(key, generation())); } catch (Exception ignored) { }
    }

    public void clear() {
        local.invalidateAll();
        if (redis != null) {
            try { redis.opsForValue().increment(namespace + ":generation"); }
            catch (Exception ignored) { }
            // A local invalidation must observe the new generation immediately;
            // do not let the polling window resurrect an old Redis value.
            generationCheckedAtNanos = 0;
        }
    }

    public CacheStats stats() { return new CacheStats(localHits.sum(), redisHits.sum(), sourceLoads.sum(), local.estimatedSize()); }

    private Entry<V> load(K key, Supplier<V> source) {
        var generation = generation();
        if (redis != null) {
            try {
                var value = redis.opsForValue().get(redisKey(key, generation));
                if (value != null) {
                    @SuppressWarnings("unchecked") V decoded = (V) json.readValue(value, valueType);
                    redisHits.increment();
                    return new Entry<>(decoded, generation);
                }
            } catch (Exception ignored) { }
        }
        var value = source.get();
        sourceLoads.increment();
        var entry = new Entry<>(value, generation);
        if (value != null) writeRedis(key, value, generation);
        return entry;
    }

    private boolean current(String entryGeneration) {
        return redis == null || Objects.equals(entryGeneration, generation());
    }

    private String generation() {
        if (redis == null) return "0";
        long now = System.nanoTime();
        if (now - generationCheckedAtNanos < GENERATION_CHECK_INTERVAL_NANOS) return generationSnapshot;
        synchronized (this) {
            now = System.nanoTime();
            if (now - generationCheckedAtNanos < GENERATION_CHECK_INTERVAL_NANOS) return generationSnapshot;
            try {
                var value = redis.opsForValue().get(namespace + ":generation");
                generationSnapshot = value == null ? "0" : value;
            } catch (Exception ignored) { generationSnapshot = "0"; }
            generationCheckedAtNanos = now;
            return generationSnapshot;
        }
    }

    private void writeRedis(K key, V value, String generation) {
        if (redis == null) return;
        try { redis.opsForValue().set(redisKey(key, generation), json.writeValueAsString(value), redisTtl); }
        catch (Exception ignored) { }
    }

    private String redisKey(K key, String generation) { return namespace + ":" + generation + ":" + keyEncoder.apply(key); }
    private record Entry<V>(V value, String generation) { }
    public record CacheStats(long localHits, long redisHits, long sourceLoads, long localEntries) { }
}

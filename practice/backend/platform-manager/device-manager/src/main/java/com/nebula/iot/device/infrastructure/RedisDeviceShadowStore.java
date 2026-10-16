package com.nebula.iot.device.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.device.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** A single persistent Redis string per tenant/device, updated atomically across JVMs. */
public final class RedisDeviceShadowStore implements DeviceShadowStore {
    private static final DefaultRedisScript<Long> CAS = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[1])
        local version = 0
        if current then version = cjson.decode(current).version end
        if version ~= tonumber(ARGV[1]) then return 0 end
        redis.call('SET', KEYS[1], ARGV[2])
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public RedisDeviceShadowStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public static String key(DeviceKey device) {
        return "nebula:shadow:v1:" + part(device.tenantId().value()) + ":" + part(device.deviceId().value());
    }
    private static String part(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    @Override public ShadowDocument read(DeviceKey key, String modelVersion) {
        var value = redis.opsForValue().get(key(key));
        if (value == null) return ShadowDocument.empty(modelVersion);
        try { return json.readValue(value, ShadowDocument.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored device shadow is invalid"); }
    }
    @Override public boolean compareAndSet(DeviceKey key, long expectedVersion, ShadowDocument document) {
        try {
            var encoded = json.writeValueAsString(document);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > 512 * 1024)
                throw new IllegalArgumentException("Device shadow exceeds 512 KiB");
            return Long.valueOf(1).equals(redis.execute(CAS, List.of(key(key)), String.valueOf(expectedVersion), encoded));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode device shadow"); }
    }
}


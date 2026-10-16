package com.nebula.iot.bootstrap.redis;

import com.fasterxml.jackson.databind.*;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.device.domain.*;
import com.nebula.iot.device.infrastructure.RedisDeviceShadowStore;
import io.lettuce.core.RedisURI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="REDIS_TEST_URL",matches="rediss?://.+")
class RedisDeviceShadowStoreTest {
    @Test void actualRedisPersistsAcrossInstancesAndAtomicallyRejectsStaleVersions() throws Exception {
        var uri=RedisURI.create(System.getenv("REDIS_TEST_URL"));
        var standalone=new RedisStandaloneConfiguration(uri.getHost(),uri.getPort());
        standalone.setDatabase(uri.getDatabase());
        if(uri.getUsername()!=null) standalone.setUsername(uri.getUsername());
        if(uri.getPassword()!=null) standalone.setPassword(new String(uri.getPassword()));
        var builder=LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5));
        if(uri.isSsl()) builder.useSsl();
        var factory=new LettuceConnectionFactory(standalone,builder.build());
        factory.afterPropertiesSet();factory.start();
        var redis=new StringRedisTemplate(factory);
        var key=new DeviceKey(new TenantId("shadow-test-"+UUID.randomUUID()),new DeviceId("isolated-fixture"));
        String redisKey=RedisDeviceShadowStore.key(key);
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            var first=new RedisDeviceShadowStore(redis,json);
            var empty=first.read(key,"v1");
            var precise=com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal("20.1234567890123456789"));
            var desired=empty.withDesired(Map.of("power",json.readTree("true"),"temperature",precise),Instant.now());
            assertTrue(first.compareAndSet(key,0,desired));
            assertEquals(-1,redis.getExpire(redisKey));
            redis.expire(redisKey,Duration.ofMinutes(5));
            assertFalse(first.compareAndSet(key,0,empty.withDesired(Map.of("power",json.readTree("false")),Instant.now())));
            var second=new RedisDeviceShadowStore(redis,json);
            var stored=second.read(key,"v1");
            assertEquals(1,stored.version());assertTrue(stored.view().delta().get("power").booleanValue());
            assertEquals(0,precise.decimalValue().compareTo(stored.desired().get("temperature").value().decimalValue()));
            var now=Instant.now();
            var report=stored.withReport(Map.of("power",json.readTree("true"),"temperature",precise),"m1",now,now);
            assertTrue(second.compareAndSet(key,1,report));
            redis.expire(redisKey,Duration.ofMinutes(5));
            assertTrue(first.read(key,"v1").view().delta().isEmpty());
            var other=new DeviceKey(new TenantId(key.tenantId().value()+"-other"),key.deviceId());
            assertEquals(0,first.read(other,"v1").version());
        } finally { try {redis.delete(redisKey);} finally {factory.destroy();} }
    }
}


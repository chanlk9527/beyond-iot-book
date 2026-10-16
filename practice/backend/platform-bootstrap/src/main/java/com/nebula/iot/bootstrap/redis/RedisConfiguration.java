package com.nebula.iot.bootstrap.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Reuse Boot's standard Redis URI, TLS, authentication and template configuration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.redis.enabled", havingValue = "true")
@Import(RedisAutoConfiguration.class)
public class RedisConfiguration {
    public RedisConfiguration(@Value("${spring.data.redis.url:}") String url) {
        if (!url.startsWith("redis://") && !url.startsWith("rediss://")) {
            throw new IllegalArgumentException("Redis requires REDIS_URL using redis:// or rediss://");
        }
    }
}

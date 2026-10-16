package com.nebula.iot.bootstrap.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RedisConfiguration.class);

    @Test void disabledDoesNotCreateRedisClient() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(RedisConnectionFactory.class));
    }

    @Test void tlsUriPreservesCredentialsAndDatabaseWithoutConnecting() {
        context.withPropertyValues("nebula.redis.enabled=true",
                "spring.data.redis.url=rediss://default:test-secret@localhost:6379/2")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StringRedisTemplate.class);
                    var factory = ctx.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
                    assertThat(factory.getStandaloneConfiguration().getUsername()).isEqualTo("default");
                    assertThat(factory.getStandaloneConfiguration().getPassword().get()).isEqualTo("test-secret".toCharArray());
                    assertThat(factory.getDatabase()).isEqualTo(2);
                });
    }

    @Test void selfHostedUriWorksWithoutTls() {
        context.withPropertyValues("nebula.redis.enabled=true",
                "spring.data.redis.url=redis://localhost:6379")
                .run(ctx -> assertThat(ctx.getBean(LettuceConnectionFactory.class)
                        .getClientConfiguration().isUseSsl()).isFalse());
    }

    @Test void enabledWithoutUrlFailsInsteadOfConnectingToLocalhost() {
        context.withPropertyValues("nebula.redis.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}

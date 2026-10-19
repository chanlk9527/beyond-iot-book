package com.nebula.iot.video.infrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.api.DeviceMediaAccess;
import com.nebula.iot.media.api.MediaProvider;
import com.nebula.iot.media.kvs.KvsMediaProvider;
import com.nebula.iot.video.api.LiveAccessPolicy;
import com.nebula.iot.video.api.AppLiveRequestVerifier;
import com.nebula.iot.video.application.LiveSessionService;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
@EnableConfigurationProperties(LiveProperties.class)
public class LiveConfiguration {
    @Bean
    @ConditionalOnProperty(name = "nebula.app.video.enabled", havingValue = "true")
    AppLiveRequestVerifier appLiveRequestVerifier(
            @org.springframework.beans.factory.annotation.Value("${nebula.app.video.app-id:}") String appId,
            @org.springframework.beans.factory.annotation.Value("${nebula.app.video.signing-secret:}") String secret,
            @org.springframework.beans.factory.annotation.Value("${nebula.app.video.signature-skew:PT60S}") Duration skew) {
        return new AppLiveRequestVerifier(appId, secret, Clock.systemUTC(), skew);
    }

    @Bean @ConditionalOnMissingBean(LiveAccessPolicy.class)
    LiveAccessPolicy liveAccessPolicy(DeviceMediaAccess devices,
            @org.springframework.beans.factory.annotation.Value("${nebula.app.video.enabled:false}") boolean appEnabled) {
        return (identity, tenant, device, channel) -> {
            var target = switch (identity.audience()) {
                case MANAGEMENT -> devices.requireWatch(identity.subject(), tenant, device);
                case APP -> {
                    if (!appEnabled) throw new AccessDeniedException("App live authorization is not enabled");
                    yield devices.requireActive(tenant, device);
                }
            };
            return new LiveAccessPolicy.Target(target.scope(), target.deviceId(), channel);
        };
    }
    @Bean @ConditionalOnMissingBean(MediaProvider.class)
    MediaProvider mediaProvider(LiveProperties p, ObjectMapper json) {
        return switch (p.getProvider()) {
            case "disabled" -> (resource, client) -> { throw new IllegalStateException("Live media disabled"); };
            case "kvs" -> {
                if (p.getRegion() == null || p.getRegion().isBlank()) throw new IllegalArgumentException("Video region required");
                yield new KvsMediaProvider(p.getRegion(), DefaultCredentialsProvider.builder().build(), json);
            }
            default -> throw new IllegalArgumentException("Unknown video provider");
        };
    }
    @Bean(destroyMethod = "close")
    LiveSessionService liveSessionService(LiveAccessPolicy policy, MediaProvider provider, LiveProperties p, ObjectMapper json) {
        return new LiveSessionService(policy, provider, p.getChannels(), Clock.systemUTC(), p.getSessionTtl(), p.getMaximumSessions(), json);
    }
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService liveSessionCleanup(LiveSessionService sessions) {
        var executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("live-session-cleanup").factory());
        executor.scheduleWithFixedDelay(sessions::sweep, 5, 5, TimeUnit.SECONDS);
        return executor;
    }
}

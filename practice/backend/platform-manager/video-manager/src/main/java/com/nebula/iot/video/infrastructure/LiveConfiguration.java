package com.nebula.iot.video.infrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.api.DeviceMediaAccess;
import com.nebula.iot.media.api.MediaProvider;
import com.nebula.iot.media.kvs.KvsMediaProvider;
import com.nebula.iot.video.api.LiveAccessPolicy;
import com.nebula.iot.video.application.LiveSessionService;
import java.time.Clock;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
@EnableConfigurationProperties(LiveProperties.class)
public class LiveConfiguration {
    @Bean @ConditionalOnMissingBean(LiveAccessPolicy.class)
    LiveAccessPolicy liveAccessPolicy(DeviceMediaAccess devices) {
        return (identity, tenant, device, channel) -> {
            if (identity.audience() != LiveAccessPolicy.Audience.MANAGEMENT)
                throw new AccessDeniedException("App live authorization is not configured");
            var target = devices.requireWatch(identity.subject(), tenant, device);
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

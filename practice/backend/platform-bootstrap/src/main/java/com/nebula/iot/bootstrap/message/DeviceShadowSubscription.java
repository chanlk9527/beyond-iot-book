package com.nebula.iot.bootstrap.message;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.device.application.DeviceShadowService;
import com.nebula.iot.message.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class DeviceShadowSubscription {
    @Bean(destroyMethod = "close")
    Subscription deviceShadowReportSubscription(EventBus bus, DeviceShadowService shadows) {
        return bus.subscribe("device/*/property/report", event -> {
            if (!(event.payload() instanceof DeviceMessageBody.PropertyReport report))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Property report required"));
            shadows.accept(new DeviceMessage(new MessageId(event.messageId()),
                    new DeviceKey(new TenantId(event.tenantId()), new DeviceId(event.deviceId())),
                    event.modelVersion(), event.occurredAt(), event.receivedAt(), MessageOrigin.valueOf(event.source()), report));
            return CompletableFuture.completedFuture(null);
        });
    }
}

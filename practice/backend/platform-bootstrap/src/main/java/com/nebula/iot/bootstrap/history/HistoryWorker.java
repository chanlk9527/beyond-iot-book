package com.nebula.iot.bootstrap.history;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.device.application.DeviceHistoryService;
import com.nebula.iot.message.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnExpression("${nebula.runtime.worker:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class HistoryWorker {
    private final DeviceHistoryService history;
    public HistoryWorker(DeviceHistoryService history) {this.history=history;}
    @Bean(destroyMethod="close")
    Subscription historySubscription(EventBus bus) {
        return bus.subscribe(SubscriptionSpec.shared("device-history-v1","device/*/property/report","device/*/event/*"),event->{
            history.accept(new DeviceMessage(new MessageId(event.messageId()),new DeviceKey(new TenantId(event.tenantId()),new DeviceId(event.deviceId())),
                    event.modelVersion(),event.occurredAt(),event.receivedAt(),MessageOrigin.valueOf(event.source()),(DeviceMessageBody)event.payload()));
            return CompletableFuture.completedFuture(null);
        });
    }
    @Scheduled(fixedDelayString="${nebula.history.cleanup-interval-ms:60000}",initialDelayString="${nebula.history.cleanup-interval-ms:60000}")
    void maintainHistory() {history.cleanup();}
}

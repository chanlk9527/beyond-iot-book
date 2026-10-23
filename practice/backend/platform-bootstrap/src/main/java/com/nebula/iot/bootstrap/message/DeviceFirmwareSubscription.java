package com.nebula.iot.bootstrap.message;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.device.application.DeviceFirmwareService;
import com.nebula.iot.message.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnExpression("${nebula.runtime.worker:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@EnableScheduling
public class DeviceFirmwareSubscription {
    @Bean(destroyMethod="close") Subscription firmwareInfoSubscription(EventBus bus,DeviceFirmwareService service) {
        return bus.subscribe(SubscriptionSpec.shared("device-firmware-info-v1","device/*/info/report"),event->accept(event,service,true));
    }
    @Bean(destroyMethod="close") Subscription firmwareVersionsSubscription(EventBus bus,DeviceFirmwareService service) {
        return bus.subscribe(SubscriptionSpec.shared("device-firmware-versions-v1","device/*/ota/version-report"),event->accept(event,service,false));
    }
    private static java.util.concurrent.CompletionStage<Void> accept(EventMessage event,DeviceFirmwareService service,boolean info) {
        String suffix=info?"info/report":"ota/version-report";
        if (!event.topic().equals("device/"+event.deviceId()+"/"+suffix)
                || info && !(event.payload() instanceof DeviceMessageBody.DeviceInfoReport)
                || !info && !(event.payload() instanceof DeviceMessageBody.OtaVersionReport))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Firmware report topic/body mismatch"));
        service.accept(new DeviceMessage(new MessageId(event.messageId()),new DeviceKey(new TenantId(event.tenantId()),new DeviceId(event.deviceId())),
                event.modelVersion(),event.occurredAt(),event.receivedAt(),MessageOrigin.valueOf(event.source()),(DeviceMessageBody)event.payload(),
                Optional.empty(),Optional.empty(),event.productId()==null?null:new ProductId(event.productId())));
        return CompletableFuture.completedFuture(null);
    }
    @Bean FirmwareReportCleanup firmwareReportCleanup(DeviceFirmwareService service) { return new FirmwareReportCleanup(service); }
    public static class FirmwareReportCleanup {
        private final DeviceFirmwareService service;
        public FirmwareReportCleanup(DeviceFirmwareService service) { this.service=service; }
        @Scheduled(fixedDelayString="${nebula.firmware.report-cleanup-interval-ms:3600000}",initialDelayString="${nebula.firmware.report-cleanup-interval-ms:3600000}")
        public void cleanup() { service.cleanup(); }
    }
}

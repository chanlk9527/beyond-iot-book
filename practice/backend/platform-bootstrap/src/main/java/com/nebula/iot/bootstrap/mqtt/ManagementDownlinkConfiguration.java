package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.device.api.DeviceDownlinkSender;
import com.nebula.iot.gateway.DeviceAccessGateway;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class ManagementDownlinkConfiguration {
    @Bean
    @ConditionalOnMissingBean(DeviceDownlinkSender.class)
    DeviceDownlinkSender managementDownlinkSender(ObjectProvider<DeviceAccessGateway> gateways) {
        return message -> {
            var gateway = gateways.getIfAvailable();
            return gateway == null ? CompletableFuture.failedFuture(new IllegalStateException("MQTT gateway unavailable")) : gateway.send(message);
        };
    }
}

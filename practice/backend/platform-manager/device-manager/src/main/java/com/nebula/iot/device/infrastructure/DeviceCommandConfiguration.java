package com.nebula.iot.device.infrastructure;

import com.nebula.iot.device.api.DeviceDownlinkSender;
import com.nebula.iot.device.application.*;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class DeviceCommandConfiguration {
    @Bean DeviceCommandService deviceCommandService(CatalogService catalog, AccountDirectory accounts,
            TenantAccess tenants, ObjectProvider<DeviceDownlinkSender> senders) {
        return new DeviceCommandService(catalog, accounts, tenants, senders::getIfAvailable, Clock.systemUTC());
    }
}

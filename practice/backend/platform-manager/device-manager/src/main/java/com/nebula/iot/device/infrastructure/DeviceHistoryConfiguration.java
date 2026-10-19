package com.nebula.iot.device.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.application.*;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.storage.HistoryStore;
import com.nebula.iot.tenant.api.TenantAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class DeviceHistoryConfiguration {
    @Bean DeviceHistoryService deviceHistoryService(CatalogService catalog,CatalogMapper data,AccountDirectory accounts,TenantAccess tenants,
            ThingModelCache models,JdbcTemplate jdbc,PlatformTransactionManager transactions,ObjectMapper json,ObjectProvider<HistoryStore> stores) {
        return new DeviceHistoryService(catalog,data,accounts,tenants,models,jdbc,new TransactionTemplate(transactions),json,stores::getIfAvailable,java.time.Clock.systemUTC());
    }
}

package com.nebula.iot.device.infrastructure;

import com.nebula.iot.device.application.*;
import com.nebula.iot.device.domain.ProductionRepository;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class ProductionConfiguration {
    @Bean ProductionRepository productionRepository(JdbcTemplate jdbc) { return new JdbcProductionRepository(jdbc); }
    @Bean ProductionService productionService(ProductionRepository repository,CatalogMapper devices,CatalogService catalog,AccountDirectory accounts,TenantAccess tenants) {
        return new ProductionService(repository,devices,catalog,accounts,tenants);
    }
}

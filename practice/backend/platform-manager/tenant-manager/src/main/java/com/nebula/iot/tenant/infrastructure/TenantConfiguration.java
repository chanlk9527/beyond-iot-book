package com.nebula.iot.tenant.infrastructure;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.application.TenantMembershipService;
import com.nebula.iot.tenant.domain.TenantMembershipRepository;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
@MapperScan(basePackageClasses = TenantMembershipMapper.class)
public class TenantConfiguration {
    @Bean com.nebula.iot.tenant.application.TenantManagementService tenantManagementService(AccountDirectory accounts, TenantMapper mapper, com.nebula.iot.identity.api.AccountProvisioning provisioning) {
        return new com.nebula.iot.tenant.application.TenantManagementService(accounts, mapper, provisioning);
    }
    @Bean com.nebula.iot.tenant.api.TenantAccess tenantAccess(TenantMembershipService memberships) {
        return memberships::requireAccessible;
    }
    @Bean TenantMembershipRepository tenantMembershipRepository(TenantMembershipMapper mapper) { return mapper::findAccessible; }
    @Bean TenantMembershipService tenantMembershipService(AccountDirectory accounts, TenantMembershipRepository repository) {
        return new TenantMembershipService(accounts, repository);
    }
}

package com.nebula.iot.identity.infrastructure;

import com.nebula.iot.identity.application.AccountService;
import com.nebula.iot.identity.application.OpenApiService;
import com.nebula.iot.identity.domain.AccountRepository;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
@MapperScan(basePackageClasses = AccountMapper.class)
public class IdentityConfiguration {
    @Bean AccountRepository accountRepository(AccountMapper mapper) { return new MyBatisAccountRepository(mapper); }
    @Bean     AccountService accountService(AccountRepository repository, AccountMapper mapper, RbacMapper rbac) { return new AccountService(repository, mapper::usernameById, rbac); }
    @Bean com.nebula.iot.identity.application.RbacService rbacService(AccountService accounts, RbacMapper rbac, org.springframework.security.crypto.password.PasswordEncoder encoder) {
        return new com.nebula.iot.identity.application.RbacService(accounts, rbac, encoder);
    }
    @Bean OpenApiService openApiService(OpenApiMapper mapper, org.springframework.security.crypto.password.PasswordEncoder encoder) {
        return new OpenApiService(mapper, encoder);
    }
}

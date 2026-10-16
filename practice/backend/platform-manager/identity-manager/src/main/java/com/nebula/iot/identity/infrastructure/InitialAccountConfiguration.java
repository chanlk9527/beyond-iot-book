package com.nebula.iot.identity.infrastructure;

import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit create-only provisioning. No default account and no password reset on restart. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class InitialAccountConfiguration {
    @Bean ApplicationRunner initialAccount(AccountMapper mapper, Environment environment, PasswordEncoder encoder) {
        return args -> {
            String username = environment.getProperty("nebula.management.initial-account.username");
            String password = environment.getProperty("nebula.management.initial-account.password");
            if (username == null && password == null) return;
            if (username == null || username.isBlank() || username.length() > 100
                    || password == null || password.isBlank() || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException("Initial account requires a username and a non-blank password of at most 72 UTF-8 bytes");
            }
            if (mapper.findByUsername(username) == null) {
                if (!environment.getProperty("nebula.management.initial-account.platform-admin", Boolean.class, false)) throw new IllegalStateException("First account must explicitly be a platform administrator; tenant users are provisioned within a tenant");
                mapper.insert(UUID.randomUUID().toString(), username, encoder.encode(password));
            }
            if (environment.getProperty("nebula.management.initial-account.platform-admin", Boolean.class, false)) {
                mapper.grantInitialPlatformAdmin(username);
            }
        };
    }
}

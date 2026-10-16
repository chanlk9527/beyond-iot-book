package com.nebula.iot.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Temporary boundary until tenant login and resource authorization are implemented. */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "false", matchIfMissing = true)
class BootstrapSecurityConfiguration {
    @Bean
    SecurityFilterChain bootstrapSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .build();
    }

    @Bean
    InMemoryUserDetailsManager bootstrapUserDetailsService() {
        // No default administrator or generated password. Tenant login is a separate milestone.
        return new InMemoryUserDetailsManager();
    }
}

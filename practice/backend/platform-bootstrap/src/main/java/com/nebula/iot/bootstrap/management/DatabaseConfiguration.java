package com.nebula.iot.bootstrap.management;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class DatabaseConfiguration {
    @Bean
    @org.springframework.core.annotation.Order(0)
    FlywayConfigurationCustomizer databaseMigrations(DataSourceProperties properties) {
        return configuration -> {
            if (properties.determineUrl().startsWith("jdbc:mysql:")) {
                configuration.locations("classpath:db/mysql/identity", "classpath:db/mysql/tenant",
                        "classpath:db/mysql/device");
            }
        };
    }
}

package com.nebula.iot.bootstrap.mqtt;

import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Keep applied cluster migrations resolvable even when cluster runtime features are disabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class AccessClusterSchemaConfiguration {
    @Bean
    @Order(100)
    FlywayConfigurationCustomizer accessClusterMigrations(DataSourceProperties database) {
        return configuration -> {
            var locations = new ArrayList<>(Arrays.stream(configuration.getLocations()).map(Object::toString).toList());
            boolean mysql = database.determineUrl().startsWith("jdbc:mysql:");
            locations.add(mysql ? "classpath:db/cluster-mysql" : "classpath:db/cluster");
            locations.add(mysql ? "classpath:db/cluster-device-mysql" : "classpath:db/cluster-device");
            configuration.locations(locations.toArray(String[]::new));
        };
    }
}
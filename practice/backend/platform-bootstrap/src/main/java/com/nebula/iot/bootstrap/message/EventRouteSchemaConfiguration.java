package com.nebula.iot.bootstrap.message;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;

/** Keep applied migrations resolvable when switching back from Dubbo to Kafka/local. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class EventRouteSchemaConfiguration {
    @Bean @Order(200)
    FlywayConfigurationCustomizer eventRouteMigrations(DataSourceProperties database) {
        return configuration->{
            var locations=new ArrayList<>(Arrays.stream(configuration.getLocations()).map(Object::toString).toList());
            locations.add(database.determineUrl().startsWith("jdbc:mysql:")?"classpath:db/message-mysql":"classpath:db/message");
            configuration.locations(locations.toArray(String[]::new));
        };
    }
}

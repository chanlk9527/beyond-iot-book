package com.nebula.iot.bootstrap.message;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import static org.junit.jupiter.api.Assertions.*;

class DubboEventConfigurationTest {
    @Test void routingMigrationsSelectDatabaseDialectWithoutReplacingDomainLocations() {
        for(String url:java.util.List.of("jdbc:mysql://localhost/test","jdbc:postgresql://localhost/test","jdbc:h2:mem:test")) {
            var source=new DataSourceProperties();source.setUrl(url);
            var configuration=Flyway.configure().locations("classpath:db/base");
            new EventRouteSchemaConfiguration().eventRouteMigrations(source).customize(configuration);
            var locations=java.util.Arrays.stream(configuration.getLocations()).map(Object::toString).toList();
            assertEquals(java.util.List.of("classpath:db/base",url.startsWith("jdbc:mysql:")?"classpath:db/message-mysql":"classpath:db/message"),locations);
        }
    }
}

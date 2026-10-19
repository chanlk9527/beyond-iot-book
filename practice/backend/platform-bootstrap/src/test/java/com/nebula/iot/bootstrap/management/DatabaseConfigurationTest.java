package com.nebula.iot.bootstrap.management;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class DatabaseConfigurationTest {
    @Test void mysqlUsesOnlyItsOwnMigrationChain() {
        assertLocations("jdbc:mysql://localhost/nebula", new String[]{
                "classpath:db/mysql/identity", "classpath:db/mysql/tenant", "classpath:db/mysql/device"});
    }

    @Test void postgresAndH2KeepExistingMigrationLocations() {
        for (String url : new String[]{"jdbc:postgresql://localhost/nebula", "jdbc:h2:mem:test"}) {
            assertLocations(url, new String[]{"classpath:db/migration/identity",
                    "classpath:db/migration/tenant", "classpath:db/migration/device"});
        }
    }

    private void assertLocations(String url, String[] expected) {
        var properties = new DataSourceProperties();
        properties.setUrl(url);
        var configuration = Flyway.configure().locations("classpath:db/migration/identity",
                "classpath:db/migration/tenant", "classpath:db/migration/device");
        new DatabaseConfiguration().databaseMigrations(properties).customize(configuration);
        // Flyway may normalize location order; migration selection depends on the set of locations.
        assertArrayEquals(java.util.Arrays.stream(expected).sorted().toArray(String[]::new),
                java.util.Arrays.stream(configuration.getLocations()).map(Object::toString).sorted().toArray(String[]::new));
    }
}

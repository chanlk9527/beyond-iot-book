package com.nebula.iot.bootstrap.mqtt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.flywaydb.core.Flyway;
import static org.junit.jupiter.api.Assertions.*;

class ClusterMigrationsTest {
    @Test void mysqlLeaseAndCommandTimesUseTheirOwnMigrationLocations() {
        for(var url:java.util.List.of("jdbc:mysql://localhost/test","jdbc:postgresql://localhost/test","jdbc:h2:mem:test")) {
            var database=new DataSourceProperties();database.setUrl(url);
            var migrations=Flyway.configure().locations("classpath:db/base");
            new AccessClusterConfiguration().accessClusterMigrations(database,true).customize(migrations);
            var locations=java.util.Arrays.stream(migrations.getLocations()).map(Object::toString).toList();
            assertTrue(locations.contains(url.startsWith("jdbc:mysql:")?"classpath:db/cluster-mysql":"classpath:db/cluster"));
            assertTrue(locations.contains(url.startsWith("jdbc:mysql:")?"classpath:db/cluster-device-mysql":"classpath:db/cluster-device"));
            assertEquals(3,locations.size());
        }
    }
}

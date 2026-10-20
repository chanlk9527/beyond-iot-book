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
            new AccessClusterSchemaConfiguration().accessClusterMigrations(database).customize(migrations);
            var locations=java.util.Arrays.stream(migrations.getLocations()).map(Object::toString).toList();
            assertTrue(locations.contains(url.startsWith("jdbc:mysql:")?"classpath:db/cluster-mysql":"classpath:db/cluster"));
            assertTrue(locations.contains(url.startsWith("jdbc:mysql:")?"classpath:db/cluster-device-mysql":"classpath:db/cluster-device"));
            assertEquals(3,locations.size());
        }
    }
    @Test void disabledClusterStillResolvesPreviouslyAppliedMigrations() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "test", java.util.Map.of("nebula.management.enabled", "true", "nebula.cluster.enabled", "false",
                            "nebula.cluster.commands.enabled", "false")));
            var database = new DataSourceProperties();
            database.setUrl("jdbc:h2:mem:cluster_migration_switch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            context.registerBean(DataSourceProperties.class, () -> database);
            context.register(AccessClusterSchemaConfiguration.class);
            context.refresh();
            var migrations = Flyway.configure().dataSource(database.getUrl(), "sa", "").locations(new String[0]);
            context.getBean("accessClusterMigrations", org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer.class)
                    .customize(migrations);
            var flyway = migrations.load();
            flyway.migrate();
            flyway.validate();
            assertEquals(java.util.Set.of("21", "22"), java.util.Arrays.stream(flyway.info().applied())
                    .map(migration -> migration.getVersion().getVersion()).collect(java.util.stream.Collectors.toSet()));
        }
    }
}

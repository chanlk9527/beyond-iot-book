package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class InfantMonitorMigrationTest {
    private static final String[] LOCATIONS = {"classpath:db/migration/identity",
            "classpath:db/migration/tenant", "classpath:db/migration/device"};

    @Test void freshDatabaseIncludesMonitor() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:monitor-fresh;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations(LOCATIONS).load().migrate();
        var jdbc = new JdbcTemplate(source);
        var tree = new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT tree_json FROM device_product_directory WHERE scope_key='platform'", String.class));
        long count = 0;
        for (var parent : tree.path("items")) {
            if (!parent.path("id").asText().equals("rest")) continue;
            for (var child : parent.path("children")) {
                if (child.path("id").asText().equals("rest-moni")) {
                    assertEquals("婴儿监视器", child.path("label").asText());
                    count++;
                }
            }
        }
        assertEquals(1, count);
    }

    @Test void upgradeAddsMonitorOnceAndPreservesCustomizedDirectories() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:monitor-upgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations(LOCATIONS).target("17").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String existing = "{\"items\":[{\"id\":\"rest\",\"label\":\"睡眠环境\",\"children\":[{\"id\":\"rest-moni\",\"label\":\"自定义监视器\",\"children\":[]}]}]}";
        jdbc.update("INSERT INTO device_product_directory(scope_key,tree_json,revision) VALUES ('existing',?,7)", existing);
        jdbc.update("INSERT INTO device_product_directory(scope_key,tree_json,revision) VALUES ('empty','{\"items\":[]}',9)");
        long before = jdbc.queryForObject("SELECT revision FROM device_product_directory WHERE scope_key='platform'", Long.class);
        var flyway = Flyway.configure().dataSource(source).locations(LOCATIONS).load();
        flyway.migrate();
        assertEquals(before + 1, jdbc.queryForObject("SELECT revision FROM device_product_directory WHERE scope_key='platform'", Long.class));
        assertEquals(existing, jdbc.queryForObject("SELECT tree_json FROM device_product_directory WHERE scope_key='existing'", String.class));
        assertEquals(7, jdbc.queryForObject("SELECT revision FROM device_product_directory WHERE scope_key='existing'", Long.class));
        assertEquals(9, jdbc.queryForObject("SELECT revision FROM device_product_directory WHERE scope_key='empty'", Long.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}

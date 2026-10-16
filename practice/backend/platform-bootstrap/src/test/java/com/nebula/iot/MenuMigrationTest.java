package com.nebula.iot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class MenuMigrationTest {
    @Test void existingRolesKeepTheirPagesWithoutGainingWritePermissions() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:menu-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        String[] locations={"classpath:db/migration/identity","classpath:db/migration/tenant","classpath:db/migration/device"};
        Flyway.configure().dataSource(source).locations(locations).target("10").load().migrate();
        var jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO identity_role(id,code,name,enabled) VALUES ('r','legacy','Legacy',FALSE),('e','empty','Empty',TRUE)");
        for (String permission:java.util.List.of("tenant:read","iam:user:read","iam:role:read","product:read","device:read")) {
            jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('r',?)",permission);
        }
        Flyway.configure().dataSource(source).locations(locations).load().migrate();
        var permissions=jdbc.queryForList("SELECT permission FROM identity_role_permission WHERE role_id='r'",String.class);
        assertEquals(12,permissions.size());
        assertTrue(permissions.containsAll(com.nebula.iot.identity.api.Menus.CATALOG.stream().map(com.nebula.iot.identity.api.Menus.Definition::code).toList()));
        assertFalse(permissions.contains("product:write"));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM identity_role WHERE id='r'",Boolean.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_role_permission WHERE role_id='e'",Integer.class));
    }
}

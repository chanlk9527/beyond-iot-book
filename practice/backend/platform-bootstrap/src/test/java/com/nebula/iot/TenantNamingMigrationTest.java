package com.nebula.iot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class TenantNamingMigrationTest {
    @Test void renamePreservesExistingTenantMembershipAuditAndForeignKeys() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:tenant-rename;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        String[] locations = {"classpath:db/migration/identity", "classpath:db/migration/tenant", "classpath:db/migration/device"};
        Flyway.configure().dataSource(source).locations(locations).target("6").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO identity_account(id,username,password_hash) VALUES ('a','member','unused')");
        jdbc.update("INSERT INTO tenant_workspace(id,name,revision) VALUES ('t','Existing tenant',3)");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('t','a','ADMIN')");
        jdbc.update("INSERT INTO tenant_audit(id,tenant_id,actor_id,action) VALUES ('audit','t','a','TENANT_CREATED')");
        Flyway.configure().dataSource(source).locations(locations).load().migrate();
        assertEquals("Existing tenant",jdbc.queryForObject("SELECT name FROM tenant WHERE id='t'",String.class));
        assertEquals(3L,jdbc.queryForObject("SELECT revision FROM tenant WHERE id='t'",Long.class));
        assertEquals("ADMIN",jdbc.queryForObject("SELECT m.role_code FROM tenant_membership m JOIN tenant t ON t.id=m.tenant_id WHERE t.id='t'",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audit a JOIN tenant t ON t.id=a.tenant_id",Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM tenant WHERE id='t'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForList("SELECT * FROM tenant_workspace"));
    }
}
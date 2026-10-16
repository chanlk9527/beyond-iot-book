package com.nebula.iot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class DeviceActivationMigrationTest {
    @Test void activatesLegacyRegistrationsPreservingCredentialsAndExplicitStops() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:activation-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        String[] locations = {"classpath:db/migration/identity","classpath:db/migration/tenant","classpath:db/migration/device"};
        Flyway.configure().dataSource(source).locations(locations).target("16").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO device_product(id,scope_key,code,name,draft_model) VALUES ('p','platform','p','Product','{}')");
        jdbc.update("INSERT INTO device_model_version(product_id,version,model_json) VALUES ('p','v1','{}')");
        for(String state : new String[]{"REGISTERED","ACTIVE","DISABLED","RETIRED"})
            jdbc.update("INSERT INTO device_registration(id,scope_key,product_id,code,name,model_version,lifecycle,credential_hash,credential_secret,revision) VALUES (?,'platform','p',?,?,'v1',?,'hash','secret',3)",state,state,state,state);
        var flyway=Flyway.configure().dataSource(source).locations(locations).load();
        flyway.migrate();
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT lifecycle FROM device_registration WHERE id='REGISTERED'",String.class));
        assertEquals(4L,jdbc.queryForObject("SELECT revision FROM device_registration WHERE id='REGISTERED'",Long.class));
        for(String state:new String[]{"ACTIVE","DISABLED","RETIRED"}){
            assertEquals(state,jdbc.queryForObject("SELECT lifecycle FROM device_registration WHERE id=?",String.class,state));
            assertEquals(3L,jdbc.queryForObject("SELECT revision FROM device_registration WHERE id=?",Long.class,state));
        }
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM device_registration WHERE credential_hash='hash' AND credential_secret='secret' AND model_version='v1'",Integer.class));
        jdbc.update("INSERT INTO device_registration(id,scope_key,product_id,code,name,model_version,credential_hash) VALUES ('new','platform','p','new','New','v1','hash')");
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT lifecycle FROM device_registration WHERE id='new'",String.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE device_registration SET lifecycle='REGISTERED' WHERE id='new'"));
        assertEquals(0,flyway.migrate().migrationsExecuted);
    }
}


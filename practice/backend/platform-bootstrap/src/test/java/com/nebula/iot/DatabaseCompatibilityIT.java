package com.nebula.iot;

import com.nebula.iot.identity.infrastructure.RbacMapper;
import com.nebula.iot.tenant.infrastructure.TenantMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: run the existing catalog HTTP/MQTT suite against a disposable real database. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseCompatibilityIT extends CatalogApiTest {
    @Autowired RbacMapper rbac;
    @Autowired TenantMapper tenants;
    @Autowired Flyway flyway;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = required("DATABASE_TEST_URL");
        if (!url.startsWith("jdbc:mysql://") && !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("DATABASE_TEST_URL must point to a disposable PostgreSQL or MySQL database");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("DATABASE_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> required("DATABASE_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> url.startsWith("jdbc:mysql:")
                ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name + " for database compatibility tests");
        return value;
    }

    @Test void allSqlAndJavaMigrationsAreAppliedAndValidated() {
        flyway.validate();
        assertEquals(18, flyway.info().applied().length);
        assertEquals(0, flyway.info().pending().length);
        for (String version : new String[]{"14", "15", "18"}) {
            var migration = Arrays.stream(flyway.info().applied())
                    .filter(m -> version.equals(m.getVersion().toString())).findFirst().orElseThrow();
            assertEquals("JDBC", migration.getType().toString());
        }
    }

    @Test void nullableScopesSearchAndPaginationWorkForUsersAndTenants() {
        assertEquals(5, rbac.userCount("", null));
        assertEquals(2, rbac.userCount("", "ta"));
        assertEquals(1, rbac.users("ALICE", 1, 0, null).size());
        assertEquals("a", rbac.users("ALICE", 1, 0, "ta").getFirst().id());
        assertTrue(rbac.users("ALICE", 1, 0, "tb").isEmpty());
        assertEquals(2, tenants.count(""));
        assertEquals(1, tenants.list("TA", 1, 0).size());
        assertEquals(1, rbac.users("", 1, 1, null).size());
    }

    @Test void identifiersAreCaseSensitiveAndForeignKeysAreEnforced() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM identity_account WHERE username='ALICE'", Integer.class));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES ('missing','business')"));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO identity_menu_permission(menu_code,permission) VALUES ('missing','product:read')"));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','o','VIEWER')"));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO identity_account(id,username,password_hash,tenant_id) VALUES ('bad','bad','unused','missing')"));
    }

    @Test @Transactional void auditTimestampsPreserveTheInstantAndMapToOffsetDateTime() {
        var instant = OffsetDateTime.parse("2026-10-19T13:45:12.123456+08:00");
        jdbc.update("INSERT INTO tenant_audit(id,tenant_id,actor_id,action,occurred_at) VALUES ('time','ta','a','TEST',?)", instant);
        assertEquals(instant.toInstant(), tenants.audits("ta").getFirst().occurredAt().toInstant());
    }
}

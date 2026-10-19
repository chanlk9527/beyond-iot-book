package com.nebula.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.device.domain.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only shared CAS store: verifies process boundaries without requiring a Redis binary in CI. */
public final class ShadowFixtureInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        new AnnotatedBeanDefinitionReader((BeanDefinitionRegistry) context.getBeanFactory()).register(StoreConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    public static class StoreConfiguration {
        @Bean DeviceShadowStore fixtureShadowStore(DataSource dataSource, ObjectMapper json,
                org.springframework.core.env.Environment environment) {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE IF NOT EXISTS fixture_shadow (tenant VARCHAR(100),device VARCHAR(100),version BIGINT,document TEXT,writer VARCHAR(30),PRIMARY KEY(tenant,device))");
            return new DeviceShadowStore() {
                public ShadowDocument read(DeviceKey key, String model) {
                    var rows = jdbc.queryForList("SELECT document FROM fixture_shadow WHERE tenant=? AND device=?", String.class,
                            key.tenantId().value(), key.deviceId().value());
                    try { return rows.isEmpty() ? ShadowDocument.empty(model) : json.readValue(rows.getFirst(), ShadowDocument.class); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                }
                public boolean compareAndSet(DeviceKey key, long expected, ShadowDocument document) {
                    try {
                        String encoded = json.writeValueAsString(document);
                        String role = environment.getProperty("nebula.runtime.role");
                        if (expected == 0) {
                            try { return jdbc.update("INSERT INTO fixture_shadow(tenant,device,version,document,writer) VALUES (?,?,?,?,?)",
                                    key.tenantId().value(),key.deviceId().value(),document.version(),encoded,role) == 1; }
                            catch (org.springframework.dao.DuplicateKeyException concurrent) { return false; }
                        }
                        return jdbc.update("UPDATE fixture_shadow SET version=?,document=?,writer=? WHERE tenant=? AND device=? AND version=?",
                                document.version(),encoded,role,key.tenantId().value(),key.deviceId().value(),expected) == 1;
                    } catch (java.io.IOException error) { throw new IllegalArgumentException(error); }
                }
            };
        }
    }
}

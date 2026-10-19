package com.nebula.iot.bootstrap;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeRoleEnvironmentTest {
    @Test void independentServiceIdentityIsFixedAndRequiresKafka() {
        var environment=new MockEnvironment().withProperty("nebula.management.enabled","true")
                .withProperty("nebula.eventbus.mode","kafka");
        environment.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource("defaultProperties",
                Map.of("nebula.runtime.service","worker")));
        new RuntimeRoleEnvironment().postProcessEnvironment(environment,new SpringApplication());
        assertEquals("worker",environment.getProperty("nebula.runtime.role"));
        assertFalse(environment.getProperty("nebula.runtime.management",Boolean.class));
        assertFalse(environment.getProperty("nebula.mqtt.enabled",Boolean.class));
        var wrongRole=new MockEnvironment().withProperty("nebula.runtime.role","access");
        wrongRole.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource("defaultProperties",
                Map.of("nebula.runtime.service","worker")));
        assertThrows(IllegalArgumentException.class,()->new RuntimeRoleEnvironment().postProcessEnvironment(wrongRole,new SpringApplication()));
        var local=new MockEnvironment().withProperty("nebula.eventbus.mode","local");
        local.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource("defaultProperties",
                Map.of("nebula.runtime.service","worker")));
        assertThrows(IllegalArgumentException.class,()->new RuntimeRoleEnvironment().postProcessEnvironment(local,new SpringApplication()));
    }
    @Test void rolesControlOnlyTheirListenersAndConsumersAndRejectUnknownValues() {
        for(var role:Map.of("management",false,"worker",false,"access",true,"all",true).entrySet()) {
            var environment=new MockEnvironment().withProperty("nebula.runtime.role",role.getKey()).withProperty("nebula.mqtt.enabled","true").withProperty("nebula.management.enabled","true");
            new RuntimeRoleEnvironment().postProcessEnvironment(environment,new SpringApplication());
            assertEquals(role.getValue(),environment.getProperty("nebula.mqtt.enabled",Boolean.class));
            assertEquals(role.getKey().equals("management")||role.getKey().equals("all"),environment.getProperty("nebula.runtime.management",Boolean.class));
            assertEquals(role.getKey().equals("worker")||role.getKey().equals("all"),environment.getProperty("nebula.runtime.worker",Boolean.class));
        }
        assertThrows(IllegalArgumentException.class,()->new RuntimeRoleEnvironment().postProcessEnvironment(new MockEnvironment().withProperty("nebula.runtime.role","typo"),new SpringApplication()));
    }
}

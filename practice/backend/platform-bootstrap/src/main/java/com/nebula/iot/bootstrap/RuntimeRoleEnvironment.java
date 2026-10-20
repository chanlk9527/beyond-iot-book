package com.nebula.iot.bootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Runtime roles select listeners, API surfaces and consumers; domain adapters may still share the database. */
public final class RuntimeRoleEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication application) {
        if(!List.of("local","kafka","dubbo").contains(environment.getProperty("nebula.eventbus.mode","local")))
            throw new IllegalArgumentException("Unknown EventBus mode");
        var defaults=environment.getPropertySources().get("defaultProperties");
        String service=defaults==null?null:(String)defaults.getProperty("nebula.runtime.service");
        if(service!=null) {
            String requested=environment.getProperty("nebula.runtime.role",service);
            if(!service.equals(requested))throw new IllegalArgumentException("This executable is fixed to role "+service);
            environment.getPropertySources().addFirst(new MapPropertySource("nebulaServiceIdentity",java.util.Map.of("nebula.runtime.role",service)));
            if(!List.of("kafka","dubbo").contains(environment.getProperty("nebula.eventbus.mode","local")))
                throw new IllegalArgumentException("Independent services require Kafka or Dubbo EventBus");
        }
        String role=environment.getProperty("nebula.runtime.role","all");
        if(!List.of("all","management","access","worker").contains(role))throw new IllegalArgumentException("Unknown runtime role: "+role);
        if(!role.equals("all") && !environment.getProperty("nebula.management.enabled",Boolean.class,false))
            throw new IllegalArgumentException("Separated runtime roles require the management profile for shared domain/database adapters");
        if(role.equals("access") && !environment.getProperty("nebula.mqtt.enabled",Boolean.class,true))
            throw new IllegalArgumentException("The access role requires MQTT to be enabled");
        var properties=new LinkedHashMap<String,Object>();
        properties.put("nebula.runtime.management",role.equals("all")||role.equals("management"));
        properties.put("nebula.runtime.worker",role.equals("all")||role.equals("worker"));
        if(role.equals("management")||role.equals("worker"))properties.put("nebula.mqtt.enabled",false);
        environment.getPropertySources().addFirst(new MapPropertySource("nebulaRuntimeRole",properties));
    }
}

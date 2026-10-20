package com.nebula.iot.bootstrap.message;

import com.nebula.iot.message.dubbo.*;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.eventbus.mode",havingValue="dubbo")
public class DubboEventConfiguration {
    @Bean
    @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
    JdbcEventRoutes eventRoutes(DataSource source,PlatformTransactionManager manager,
            @Value("${nebula.eventbus.namespace:nebula}") String namespace) {
        var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);
        var tx=new TransactionTemplate(manager);tx.setTimeout(5);
        return new JdbcEventRoutes(jdbc,tx,namespace,Duration.ofSeconds(10));
    }
    @Bean
    DubboEventBus dubboEventBus(JdbcEventRoutes routes,
            @Value("${nebula.eventbus.namespace:nebula}") String namespace,
            @Value("${nebula.eventbus.node-id:node}") String logicalNode,
            @Value("${nebula.eventbus.dubbo.host:127.0.0.1}") String host,
            @Value("${nebula.eventbus.dubbo.port:20880}") int port,
            @Value("${nebula.eventbus.dubbo.credential:}") String credential,
            @Value("${nebula.eventbus.handler-timeout:30s}") Duration timeout,
            @Value("${nebula.eventbus.dubbo.maximum-pending:100000}") int maximumPending,
            @Value("${nebula.runtime.role:all}") String role,
            @Value("${nebula.eventbus.dubbo.discovery:nacos}") String discoveryMode,
            @Value("${nebula.eventbus.dubbo.nacos.server-addr:127.0.0.1:8848}") String servers,
            @Value("${nebula.eventbus.dubbo.nacos.namespace:}") String nacosNamespace,
            @Value("${nebula.eventbus.dubbo.nacos.group:BEYOND_IOT}") String group,
            @Value("${nebula.eventbus.dubbo.nacos.service:nebula-eventbus}") String service,
            @Value("${nebula.eventbus.dubbo.nacos.username:}") String username,
            @Value("${nebula.eventbus.dubbo.nacos.password:}") String password) {
        if(logicalNode.length()>63)throw new IllegalArgumentException("Dubbo node name must be at most 63 characters");
        String processNode=(logicalNode.isBlank()?"node":logicalNode)+"-"+UUID.randomUUID();
        EventNodeDiscovery discovery=switch(discoveryMode) {
            case "nacos" -> new NacosEventNodeDiscovery(servers,nacosNamespace,group,service,username,password,namespace);
            case "jdbc" -> null; // Explicit legacy/test compatibility; never an automatic fallback.
            default -> throw new IllegalArgumentException("Unknown EventBus discovery mode: "+discoveryMode);
        };
        var bus=new DubboEventBus(routes,namespace,processNode,host,port,credential,timeout,maximumPending,discovery);
        bus.setRole(role);
        return bus;
    }
    @Bean HealthIndicator eventRoutingHealthIndicator(DubboEventBus bus) {
        return ()->bus.healthy()?Health.up().build():Health.down().build();
    }
}

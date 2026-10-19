package com.nebula.iot.bootstrap.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.cluster.AccessNodeAgent;
import com.nebula.iot.cluster.JdbcNodeDirectory;
import com.nebula.iot.gateway.JdbcDeviceSessionDirectory;
import com.nebula.iot.network.mqtt.VertxMqttGateway;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in membership foundation. Does not enable reliable messaging or claim full data-plane clustering. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.cluster.enabled",havingValue="true")
public class AccessClusterConfiguration {
    public record Instance(String id,String name) {}
    @Bean Instance accessInstance(@Value("${nebula.access.node-name:local-access}") String name) {
        if(name.isBlank()||name.length()>128)throw new IllegalArgumentException("Invalid access node name");
        return new Instance(UUID.randomUUID().toString(),name);
    }
    @Bean @Order(100)
    FlywayConfigurationCustomizer accessClusterMigrations(org.springframework.boot.autoconfigure.jdbc.DataSourceProperties database, @Value("${nebula.cluster.commands.enabled:false}") boolean commands) {
        return configuration -> {
            var locations=new java.util.ArrayList<>(Arrays.stream(configuration.getLocations()).map(Object::toString).toList());
            boolean mysql=database.determineUrl().startsWith("jdbc:mysql:");
            locations.add(mysql?"classpath:db/cluster-mysql":"classpath:db/cluster");
            if(commands)locations.add(mysql?"classpath:db/cluster-device-mysql":"classpath:db/cluster-device");
            configuration.locations(locations.toArray(String[]::new));
        };
    }
    @Bean JdbcNodeDirectory accessNodeDirectory(DataSource source,PlatformTransactionManager manager,
            @Value("${nebula.cluster.lease:PT15S}") Duration lease) {
        var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);
        var tx=new TransactionTemplate(manager);tx.setTimeout(5);
        return new JdbcNodeDirectory(jdbc,tx,lease);
    }
    @Bean JdbcDeviceSessionDirectory deviceSessionDirectory(DataSource source,PlatformTransactionManager manager,
            Instance instance,@Value("${nebula.cluster.lease:PT15S}") Duration lease) {
        var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);
        var tx=new TransactionTemplate(manager);tx.setTimeout(5);
        return new JdbcDeviceSessionDirectory(jdbc,tx,instance.id(),lease);
    }
    @Bean @ConditionalOnProperty(name="nebula.mqtt.enabled",havingValue="true",matchIfMissing=true)
    AccessNodeAgent accessNodeAgent(JdbcNodeDirectory directory,Instance instance,VertxMqttGateway gateway,ObjectMapper mapper,
            @Value("${nebula.cluster.lease:PT15S}") Duration lease) {
        return new AccessNodeAgent(directory,instance.id(),instance.name(),lease,new AccessNodeAgent.LocalAccess() {
            public boolean running(){return gateway.isRunning();}
            public int connections(){return gateway.connectionCount();}
            public int sessions(){return gateway.sessionCount();}
            public int capacity(){return gateway.runtimeSnapshot().maxConnections();}
            public String metricsJson(){try{return mapper.writeValueAsString(gateway.runtimeSnapshot());}catch(JsonProcessingException error){throw new IllegalStateException(error);}}
            public void admit(boolean enabled){if(enabled)gateway.resumeAdmissions();else gateway.pauseAdmissions();}
            public java.util.concurrent.CompletionStage<Integer> disconnectBatch(int count){return gateway.disconnectBatch(count);}
        });
    }
    @Bean SmartInitializingSingleton configureClusterOwnership(ObjectProvider<AccessNodeAgent> agents,
            ObjectProvider<VertxMqttGateway> gateways,JdbcDeviceSessionDirectory sessions,ObjectProvider<com.nebula.iot.bootstrap.subscription.DevicePresence> presence) {
        return () -> {
            presence.ifAvailable(value -> value.setDirectory(sessions));
            var gateway=gateways.getIfAvailable();var agent=agents.getIfAvailable();
            if(gateway!=null && agent!=null)gateway.configureOwnership(sessions,agent::admitting,agent::serving);
        };
    }
    @Bean @ConditionalOnBean(AccessNodeAgent.class)
    SmartLifecycle clusterNodeLifecycle(AccessNodeAgent agent) {
        return new SmartLifecycle() {
            public int getPhase(){return 100;}
            public boolean isRunning(){return agent.healthy();}
            public void start(){agent.start();}
            public void stop(){agent.close();}
        };
    }
    @Bean HealthIndicator clusterMembershipHealthIndicator(ObjectProvider<AccessNodeAgent> agents,JdbcNodeDirectory directory) {
        return () -> {
            try {
                directory.databaseTime();var agent=agents.getIfAvailable();
                return (agent==null||agent.healthy()&&agent.admitting()?Health.up():Health.down()).build();
            }catch(RuntimeException error){return Health.down().build();}
        };
    }
}

package com.nebula.iot.bootstrap.message;

import com.nebula.iot.message.dubbo.DubboEventBus;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises production bean wiring and its default discovery choice, with isolated SQL and Nacos service. */
@EnabledIfSystemProperty(named="nacos.test.address",matches=".+")
class NacosBootstrapIntegrationTest {
    @Test void springLifecycleRegistersWithNacosByDefault() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");source.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("db/message/V26__dubbo_event_routing.sql"),
                new ClassPathResource("db/message/V27__dynamic_route_lifecycle.sql"),
                new ClassPathResource("db/message/V28__event_source_scope.sql")).execute(source);
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        new ApplicationContextRunner()
                .withInitializer(context->context.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(DubboEventConfiguration.class)
                .withBean(DataSource.class,()->source)
                .withBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source))
                .withPropertyValues("nebula.eventbus.mode=dubbo","nebula.eventbus.namespace=bootstrap-test",
                        "nebula.eventbus.node-id=spring","nebula.runtime.role=worker","nebula.eventbus.dubbo.port="+port,
                        "nebula.eventbus.dubbo.credential=isolated-bootstrap-test-credential-32",
                        "nebula.eventbus.dubbo.nacos.server-addr="+System.getProperty("nacos.test.address"),
                        "nebula.eventbus.dubbo.nacos.namespace="+System.getProperty("nacos.test.namespace",""),
                        "nebula.eventbus.dubbo.nacos.username="+System.getProperty("nacos.test.username",""),
                        "nebula.eventbus.dubbo.nacos.password="+System.getProperty("nacos.test.password",""),
                        "nebula.eventbus.dubbo.nacos.group=BEYOND_IOT_TEST",
                        "nebula.eventbus.dubbo.nacos.service=eventbus-spring-"+UUID.randomUUID())
                .run(context->{
                    assertNull(context.getStartupFailure());
                    var bus=context.getBean(DubboEventBus.class);
                    assertTrue(bus.isRunning());assertTrue(bus.healthy());
                    assertEquals("worker",bus.runtimeSnapshot().role());
                    assertTrue(bus.runtimeSnapshot().nodeId().startsWith("spring-"));
                });
    }
}

package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.network.mqtt.VertxMqttGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessNodeControllerTest {
    @Test void historicalInstancesAreOptInAndDoNotInvalidateCurrentMetrics() throws Exception {
        var data=new org.h2.jdbcx.JdbcDataSource();
        data.setURL("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/cluster/V21__access_cluster.sql")).execute(data);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(data);
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(data));
        var directory=new com.nebula.iot.cluster.JdbcNodeDirectory(jdbc,tx,java.time.Duration.ofSeconds(15));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var metrics=new VertxMqttGateway.RuntimeSnapshot(true,"127.0.0.1",1883,1,1,1000,7,0,0,4,0,256);
        directory.register("current","access-a",1000);directory.heartbeat("current",1,mapper.writeValueAsString(metrics));
        directory.register("previous","access-a",1000);directory.heartbeat("previous",0,"null");
        jdbc.update("UPDATE cluster_access_node SET lease_until=?,heartbeat_at=? WHERE node_id='previous'",
                java.sql.Timestamp.from(java.time.Instant.EPOCH),java.sql.Timestamp.from(java.time.Instant.EPOCH));
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("directory",directory);
        beans.registerSingleton("sessions",new com.nebula.iot.gateway.JdbcDeviceSessionDirectory(jdbc,tx,"observer",java.time.Duration.ofSeconds(15)));
        var controller=new AccessNodeController(mock(AccountDirectory.class),beans.getBeanProvider(VertxMqttGateway.class),
                beans.getBeanProvider(com.nebula.iot.cluster.JdbcNodeDirectory.class),
                beans.getBeanProvider(com.nebula.iot.gateway.JdbcDeviceSessionDirectory.class),mapper,"management");
        var current=controller.snapshot(()->"admin").getBody();
        assertEquals(1,current.totalNodes());assertEquals("current",current.nodes().getFirst().nodeId());assertTrue(current.complete());
        var history=controller.snapshot(()->"admin",true).getBody();
        assertEquals(2,history.totalNodes());assertEquals("UNREACHABLE",history.nodes().get(1).lifecycle());assertTrue(history.complete());
        jdbc.update("UPDATE cluster_access_node SET heartbeat_at=? WHERE node_id='previous'",
                java.time.LocalDateTime.ofInstant(directory.databaseTime(),java.time.ZoneOffset.UTC));
        var failure=controller.snapshot(()->"admin").getBody();
        assertEquals(2,failure.totalNodes());assertFalse(failure.complete());
    }
    @Test void disabledGatewayHasNoInventedMetricsAndKeepsItsInstanceIdentity() {
        var accounts=mock(AccountDirectory.class);
        var beans=new StaticListableBeanFactory();
        var controller=new AccessNodeController(accounts,beans.getBeanProvider(VertxMqttGateway.class),
                beans.getBeanProvider(com.nebula.iot.cluster.JdbcNodeDirectory.class),
                beans.getBeanProvider(com.nebula.iot.gateway.JdbcDeviceSessionDirectory.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),"access-local");
        var first=controller.snapshot(()->"admin").getBody();
        var second=controller.snapshot(()->"admin").getBody();
        assertNotNull(first);
        assertEquals("LOCAL_PROCESS",first.scope());
        assertEquals("DISABLED",first.nodes().getFirst().status());
        assertNull(first.nodes().getFirst().metrics());
        assertEquals(first.nodes().getFirst().nodeId(),second.nodes().getFirst().nodeId());
        assertTrue(first.nodes().getFirst().processUptimeMillis()>=0);
        verify(accounts,times(2)).requirePlatformAdmin("admin");
    }
}

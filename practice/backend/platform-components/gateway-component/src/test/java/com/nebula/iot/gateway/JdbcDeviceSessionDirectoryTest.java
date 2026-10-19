package com.nebula.iot.gateway;

import com.nebula.iot.cluster.JdbcNodeDirectory;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import java.time.Duration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class JdbcDeviceSessionDirectoryTest {
    JdbcTemplate jdbc;
    JdbcDeviceSessionDirectory a,b;
    JdbcNodeDirectory nodes;
    final DeviceKey device=new DeviceKey(new TenantId("tenant"),new DeviceId("warmer"));
    @BeforeEach void setup() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/cluster/V21__access_cluster.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        nodes=new JdbcNodeDirectory(jdbc,tx,Duration.ofSeconds(15));
        for(var id:java.util.List.of("a","b")){nodes.register(id,id,10);nodes.heartbeat(id,0,"null");}
        a=new JdbcDeviceSessionDirectory(jdbc,tx,"a",Duration.ofSeconds(15));
        b=new JdbcDeviceSessionDirectory(jdbc,tx,"b",Duration.ofSeconds(15));
    }
    @Test void lateReleaseAndRenewalCannotAffectTheReplacementSession() {
        var old=a.acquire(device,"client");var replacement=b.acquire(device,"client");
        assertTrue(replacement.epoch()>old.epoch());
        assertThrows(IllegalStateException.class,()->a.renew(device,old));
        a.release(device,old);
        assertEquals(replacement.sessionId(),b.owner(device).sessionId());
        assertEquals(1,b.activeCount());
        b.release(device,replacement);assertNull(a.owner(device));
        var next=a.acquire(device,"client");assertTrue(next.epoch()>replacement.epoch());
    }
    @Test void clientIdentityCannotBeHijackedAcrossNodesOrTenants() {
        a.acquire(device,"client");
        var other=new DeviceKey(new TenantId("other"),new DeviceId("warmer"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->b.acquire(other,"client"));
        assertEquals("a",a.owner(device).nodeId());
        nodes.offline("a");
        b.acquire(other,"client");assertEquals("b",b.owner(other).nodeId());
    }
    @Test void nodeAndSessionExpiryAreBothEnforcedAndDrainingRejectsNewSessions() {
        var lease=a.acquire(device,"client");
        nodes.requestDrain("drain","a",0,"admin",1,Duration.ofSeconds(20));
        assertDoesNotThrow(()->a.renew(device,lease));
        assertThrows(IllegalStateException.class,()->a.acquire(device,"client"));
        jdbc.update("UPDATE gateway_device_session SET lease_until=?",java.sql.Timestamp.from(java.time.Instant.EPOCH));
        assertThrows(IllegalStateException.class,()->a.renew(device,lease));
        assertNull(a.owner(device));
    }
    @Test void concurrentFirstConnectionsSerializeTheirSessionGenerations() throws Exception {
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{ready.countDown();start.await();return a.acquire(device,"client");});
            var second=pool.submit(()->{ready.countDown();start.await();return b.acquire(device,"client");});
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            var one=first.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var two=second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(one.epoch(),two.epoch());
            assertEquals(Math.max(one.epoch(),two.epoch()),a.owner(device).epoch());
            assertEquals(1,a.activeCount());
        }
    }
}

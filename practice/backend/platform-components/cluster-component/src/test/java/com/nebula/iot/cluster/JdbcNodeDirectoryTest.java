package com.nebula.iot.cluster;

import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class JdbcNodeDirectoryTest {
    JdbcTemplate jdbc;
    JdbcNodeDirectory directory;
    @BeforeEach void setup() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/cluster/V21__access_cluster.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);
        directory=new JdbcNodeDirectory(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),Duration.ofSeconds(15));
    }
    void active(String id,int connections,int capacity) {
        directory.register(id,id,capacity);directory.heartbeat(id,connections,"null");
    }
    @Test void overviewHidesHistoryBeforeLimitingButKeepsRecentFailures() {
        active("current",1,10);
        jdbc.update("UPDATE cluster_access_node SET started_at=? WHERE node_id='current'",Timestamp.from(Instant.EPOCH));
        active("old",0,10);
        jdbc.update("UPDATE cluster_access_node SET lease_until=?,heartbeat_at=? WHERE node_id='old'",
                Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH));
        var now=directory.databaseTime();var recent=now.minusSeconds(120);
        assertEquals("current",directory.overview(now,recent,false,1).getFirst().nodeId());
        assertEquals("current",directory.overview(now,recent,true,1).getFirst().nodeId());
        active("lost",0,10);
        jdbc.update("UPDATE cluster_access_node SET lease_until=? WHERE node_id='lost'",Timestamp.from(Instant.EPOCH));
        active("closed",0,10);directory.offline("closed");
        assertEquals(2,directory.overviewSize(now,recent,false));
        assertEquals(4,directory.overviewSize(now,recent,true));
        assertTrue(directory.overview(now,recent,false,10).stream().anyMatch(n->n.nodeId().equals("lost")));
        jdbc.update("UPDATE cluster_access_node SET heartbeat_at=? WHERE node_id='lost'",
                java.time.LocalDateTime.ofInstant(now.minusSeconds(121),java.time.ZoneOffset.UTC));
        assertEquals(1,directory.overviewSize(now,recent,false));
    }
    @Test void anExpiredIdentityCannotResurrectByHeartbeat() {
        active("a",1,10);
        jdbc.update("UPDATE cluster_access_node SET lease_until=? WHERE node_id='a'",Timestamp.from(Instant.EPOCH));
        assertThrows(IllegalStateException.class,()->directory.heartbeat("a",0,"null"));
        directory.register("new-a","a",10);
        assertEquals("ACTIVE",directory.heartbeat("new-a",0,"null").state());
    }
    @Test void drainIsDurableIdempotentAndPreventsDoubleSpendingSpareCapacity() {
        active("a",8,10);active("b",8,10);active("c",8,10);
        // Only four spare connections: an eight-connection node cannot drain.
        assertThrows(IllegalStateException.class,()->directory.requestDrain("op-a","a",0,"admin",1,Duration.ofSeconds(20)));
        directory.heartbeat("c",0,"null");
        var first=directory.requestDrain("op-a","a",0,"admin",1,Duration.ofSeconds(20));
        assertEquals(first,directory.requestDrain("op-a","a",0,"admin",1,Duration.ofSeconds(20)));
        assertEquals("DRAINING",directory.find("a").state());
        assertThrows(IllegalStateException.class,()->directory.requestDrain("op-a","b",0,"admin",1,Duration.ofSeconds(20)));
        // Draining a no longer contributes its spare capacity to another drain.
        directory.heartbeat("c",4,"null");
        assertThrows(IllegalStateException.class,()->directory.requestDrain("op-b","b",0,"admin",1,Duration.ofSeconds(20)));
        directory.drainProgress("op-a","a",0);
        assertEquals("SUCCEEDED",directory.operation("op-a").state());
        var resumed=directory.resume("a",1,"resume-a","admin");
        assertEquals("RESUME",resumed.type());
        assertEquals("ACTIVE",directory.find("a").state());
        assertEquals(resumed,directory.resume("a",1,"resume-a","admin"));
    }
    @Test void drainCannotTargetTheOnlyLiveNodeAndLostTargetsAreRecordedAsFailed() {
        active("a",1,10);
        assertThrows(IllegalStateException.class,()->directory.requestDrain("op-a","a",0,"admin",1,Duration.ofSeconds(20)));
        active("b",0,10);
        directory.requestDrain("op-a","a",0,"admin",1,Duration.ofSeconds(20));
        directory.offline("a");directory.expireOperations();
        assertEquals("FAILED",directory.operation("op-a").state());
    }
    @Test void repeatedShutdownDoesNotRepeatTheNodeExitTransition() {
        var access=new AccessNodeAgent.LocalAccess(){
            public boolean running(){return true;}
            public int connections(){return 0;}
            public int sessions(){return 0;}
            public int capacity(){return 10;}
            public String metricsJson(){return "null";}
            public void admit(boolean enabled){}
            public java.util.concurrent.CompletionStage<Integer> disconnectBatch(int limit){return java.util.concurrent.CompletableFuture.completedFuture(0);}
        };
        var agent=new AccessNodeAgent(directory,"a","a",Duration.ofSeconds(15),access);
        agent.start();agent.close();agent.close();
        assertEquals("OFFLINE",directory.find("a").state());
        assertEquals(1,directory.find("a").revision());
    }
}

package com.nebula.iot.gateway;

import com.nebula.iot.core.device.DeviceKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Rows retain their generation after release; stale callbacks can only release their own token. */
public final class JdbcDeviceSessionDirectory implements SessionOwnership {
    public record Owner(String nodeId, String sessionId, long epoch, Instant leaseUntil) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String nodeId;
    private final Duration duration;
    private final com.nebula.iot.cluster.DatabaseTime time;

    public JdbcDeviceSessionDirectory(JdbcTemplate jdbc, TransactionTemplate transactions, String nodeId, Duration duration) {
        this.jdbc=jdbc; this.transactions=transactions; this.nodeId=nodeId; this.duration=duration; this.time=new com.nebula.iot.cluster.DatabaseTime(jdbc);
    }

    @Override public Lease acquire(DeviceKey key, String clientId) {
        long began = System.nanoTime();
        // Seed outside the transaction: a concurrent insert conflict must not poison a PostgreSQL transaction.
        try {
            jdbc.update("INSERT INTO gateway_device_session(tenant_id,device_id,node_id,session_id,epoch,lease_until) VALUES (?,?,NULL,NULL,0,?)",
                    tenant(key), device(key), utc(Instant.EPOCH));
        } catch (DuplicateKeyException alreadySeeded) { }
        var cleanupTime=utc(now());
        jdbc.update("UPDATE gateway_device_session SET client_id=NULL WHERE client_id=? AND (lease_until<=? OR node_id IN (SELECT node_id FROM cluster_access_node WHERE lease_until<=? OR node_state='OFFLINE'))",
                clientId,cleanupTime,cleanupTime);
        return transactions.execute(status -> {
            // Node lock precedes session lock consistently with heartbeat and maintenance requests.
            requireNode(true);
            long epoch = Objects.requireNonNull(jdbc.queryForObject("SELECT epoch FROM gateway_device_session WHERE tenant_id=? AND device_id=? FOR UPDATE", Long.class, tenant(key), device(key)));
            var id = UUID.randomUUID().toString();
            var now = now();
            jdbc.update("UPDATE gateway_device_session SET node_id=?,session_id=?,epoch=?,lease_until=?,client_id=? WHERE tenant_id=? AND device_id=?",
                    nodeId,id,Math.addExact(epoch,1),utc(now.plus(duration)),clientId,tenant(key),device(key));
            return budget(id,epoch+1,began);
        });
    }

    @Override public Lease renew(DeviceKey key, Lease lease) {
        long began=System.nanoTime();
        return transactions.execute(status -> {
            requireNode(false);
            var now=now();
            int count=jdbc.update("UPDATE gateway_device_session SET lease_until=? WHERE tenant_id=? AND device_id=? AND node_id=? AND session_id=? AND epoch=? AND lease_until>?",
                    utc(now.plus(duration)),tenant(key),device(key),nodeId,lease.sessionId(),lease.epoch(),utc(now));
            if(count!=1) throw new IllegalStateException("Device session ownership lost");
            return budget(lease.sessionId(),lease.epoch(),began);
        });
    }

    @Override public void release(DeviceKey key, Lease lease) {
        jdbc.update("UPDATE gateway_device_session SET node_id=NULL,session_id=NULL,client_id=NULL,lease_until=? WHERE tenant_id=? AND device_id=? AND node_id=? AND session_id=? AND epoch=?",
                utc(Instant.EPOCH),tenant(key),device(key),nodeId,lease.sessionId(),lease.epoch());
    }

    public Owner owner(DeviceKey key) {
        var rows=jdbc.query("SELECT s.node_id,s.session_id,s.epoch,s.lease_until FROM gateway_device_session s JOIN cluster_access_node n ON n.node_id=s.node_id WHERE s.tenant_id=? AND s.device_id=? AND s.lease_until>? AND n.lease_until>? AND n.node_state IN ('ACTIVE','DRAINING')",
                (rs,index)->new Owner(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getObject(4,java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC)),tenant(key),device(key),utc(now()),utc(now()));
        return rows.isEmpty()?null:rows.getFirst();
    }

    public long activeCount() {
        var now=utc(now());
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_device_session s JOIN cluster_access_node n ON n.node_id=s.node_id WHERE s.lease_until>? AND n.lease_until>? AND n.node_state IN ('ACTIVE','DRAINING')",Long.class,now,now));
    }

    private void requireNode(boolean admission) {
        var rows=jdbc.query("SELECT node_state,lease_until FROM cluster_access_node WHERE node_id=? FOR UPDATE",
                (rs,index)->new Object[]{rs.getString(1),rs.getObject(2,java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC)},nodeId);
        if(rows.isEmpty()) throw new IllegalStateException("Node is unregistered");
        var node=rows.getFirst();
        if(!((Instant)node[1]).isAfter(now()) || !(node[0].equals("ACTIVE") || !admission && node[0].equals("DRAINING")))
            throw new IllegalStateException("Node has no valid ownership lease");
    }
    private Instant now() { return time.now(); }
    private Lease budget(String id,long epoch,long began) {
        long remaining=duration.toMillis()-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)-50;
        if(remaining<=0) throw new IllegalStateException("Lease acquisition exceeded its safety budget");
        return new Lease(id,epoch,remaining);
    }
    private static java.time.LocalDateTime utc(Instant value) {return java.time.LocalDateTime.ofInstant(value,java.time.ZoneOffset.UTC);}
    private static String tenant(DeviceKey key) {return key.tenantId().value();}
    private static String device(DeviceKey key) {return key.deviceId().value();}
}

package com.nebula.iot.cluster;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared membership and durable maintenance intent. All lease decisions use database time. */
public final class JdbcNodeDirectory {
    public record Node(String nodeId, String name, String state, Instant startedAt, Instant lastHeartbeat,
                       Instant leaseUntil, int connections, int maxConnections, String metricsJson, long revision) {}
    public record Drain(String operationId, String nodeId, String requestedBy, String state, int batchSize,
                        Instant deadline, int remaining, Instant createdAt, String failure, String type) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Duration lease;
    private final DatabaseTime time;

    public JdbcNodeDirectory(JdbcTemplate jdbc, TransactionTemplate transactions, Duration lease) {
        if (lease.toMillis() < 5000 || lease.toMillis() > 300_000) throw new IllegalArgumentException("Node lease out of range");
        this.jdbc = jdbc; this.transactions = transactions; this.lease = lease; this.time=new DatabaseTime(jdbc);
    }

    public Instant databaseTime() {
        return time.now();
    }

    public void register(String nodeId, String name, int maxConnections) {
        var now = databaseTime();
        jdbc.update("INSERT INTO cluster_access_node(node_id,node_name,node_state,started_at,heartbeat_at,lease_until,connections,max_connections,metrics_json,revision) VALUES (?,?,'STARTING',?,?,?,0,?,'null',0)",
                nodeId, name, ts(now), ts(now), ts(now.plus(lease)), maxConnections);
    }

    /** An expired process identity is never resurrected by heartbeat. */
    public Node heartbeat(String nodeId, int connections, String metricsJson) {
        return transactions.execute(status -> {
            var node = lock(nodeId);
            var now = databaseTime();
            if (!node.leaseUntil().isAfter(now) || List.of("OFFLINE", "STOPPING").contains(node.state()))
                throw new IllegalStateException("Node lease lost; restart with a new instance identity");
            jdbc.update("UPDATE cluster_access_node SET node_state=?,heartbeat_at=?,lease_until=?,connections=?,metrics_json=? WHERE node_id=?",
                    node.state().equals("STARTING") ? "ACTIVE" : node.state(), ts(now), ts(now.plus(lease)), connections, metricsJson, nodeId);
            return find(nodeId);
        });
    }

    public Node find(String nodeId) {
        var rows = jdbc.query("SELECT * FROM cluster_access_node WHERE node_id=?", JdbcNodeDirectory::node, nodeId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown node");
        return rows.getFirst();
    }

    public List<Node> list(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Node page out of range");
        return jdbc.query("SELECT * FROM cluster_access_node ORDER BY started_at DESC,node_id LIMIT ?", JdbcNodeDirectory::node, limit);
    }

    public long size() { return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM cluster_access_node", Long.class)); }

    /** Filter before limiting so old instances cannot crowd current nodes out of the overview. */
    public List<Node> overview(Instant now,Instant recentHeartbeat,boolean includeHistory,int limit) {
        if(limit<1||limit>1000)throw new IllegalArgumentException("Invalid node limit");
        String current="node_state<>'OFFLINE' AND (lease_until>? OR heartbeat_at>?)";
        if(!includeHistory)return jdbc.query("SELECT * FROM cluster_access_node WHERE "+current+" ORDER BY started_at DESC,node_id LIMIT ?",
                JdbcNodeDirectory::node,ts(now),ts(recentHeartbeat),limit);
        return jdbc.query("SELECT * FROM cluster_access_node ORDER BY CASE WHEN "+current+" THEN 0 ELSE 1 END,started_at DESC,node_id LIMIT ?",
                JdbcNodeDirectory::node,ts(now),ts(recentHeartbeat),limit);
    }

    public long overviewSize(Instant now,Instant recentHeartbeat,boolean includeHistory) {
        if(includeHistory)return size();
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM cluster_access_node WHERE node_state<>'OFFLINE' AND (lease_until>? OR heartbeat_at>?)",
                Long.class,ts(now),ts(recentHeartbeat)));
    }

    private Node lock(String nodeId) {
        var rows = jdbc.query("SELECT * FROM cluster_access_node WHERE node_id=? FOR UPDATE", JdbcNodeDirectory::node, nodeId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown node");
        return rows.getFirst();
    }

    /** Serializes competing capacity reservations so two nodes cannot simultaneously consume the same spare capacity. */
    public Drain requestDrain(String operationId, String nodeId, long revision, String actor, int batchSize, Duration timeout) {
        if (!operationId.matches("[A-Za-z0-9_-]{1,64}") || batchSize < 1 || batchSize > 1000
                || timeout.toSeconds() < 1 || timeout.toSeconds() > 3600) throw new IllegalArgumentException("Invalid drain request");
        return transactions.execute(status -> {
            // A singleton row is only locked for admission, never while disconnecting devices.
            jdbc.queryForObject("SELECT lock_id FROM cluster_control_lock WHERE lock_id=1 FOR UPDATE", Integer.class);
            var previous = jdbc.query("SELECT * FROM cluster_node_operation WHERE operation_id=?", JdbcNodeDirectory::drain, operationId);
            if (!previous.isEmpty()) {
                var op = previous.getFirst();
                if (!op.nodeId().equals(nodeId) || !op.requestedBy().equals(actor) || op.batchSize() != batchSize
                        || Duration.between(op.createdAt(), op.deadline()).toSeconds() != timeout.toSeconds())
                    throw new IllegalStateException("Operation ID reused with different content");
                return op;
            }
            var target = lock(nodeId);
            var now = databaseTime();
            if (target.revision() != revision || !target.state().equals("ACTIVE") || !target.leaseUntil().isAfter(now))
                throw new IllegalStateException("Node changed or is not active");
            var spare = jdbc.queryForObject("SELECT COALESCE(SUM(max_connections-connections),0) FROM cluster_access_node WHERE node_state='ACTIVE' AND node_id<>? AND lease_until>? AND heartbeat_at>?",
                    Long.class, nodeId, ts(now), ts(now.minus(lease.dividedBy(2))));
            var peers = jdbc.queryForObject("SELECT COUNT(*) FROM cluster_access_node WHERE node_state='ACTIVE' AND node_id<>? AND lease_until>? AND heartbeat_at>?",
                    Long.class, nodeId, ts(now), ts(now.minus(lease.dividedBy(2))));
            if (peers == null || peers == 0 || spare == null || spare < target.connections())
                throw new IllegalStateException("Insufficient fresh capacity on other nodes");
            jdbc.update("UPDATE cluster_access_node SET node_state='DRAINING',revision=revision+1 WHERE node_id=?", nodeId);
            jdbc.update("INSERT INTO cluster_node_operation(operation_id,node_id,requested_by,operation_state,batch_size,deadline,remaining,created_at,failure) VALUES (?,?,?,'RUNNING',?,?,?,?,NULL)",
                    operationId, nodeId, actor, batchSize, ts(now.plus(timeout)), target.connections(), ts(now));
            return operation(operationId);
        });
    }

    public Drain activeDrain(String nodeId) {
        var values = jdbc.query("SELECT * FROM cluster_node_operation WHERE node_id=? AND operation_state='RUNNING' ORDER BY created_at LIMIT 1", JdbcNodeDirectory::drain, nodeId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public Drain operation(String id) {
        var values = jdbc.query("SELECT * FROM cluster_node_operation WHERE operation_id=?", JdbcNodeDirectory::drain, id);
        if (values.isEmpty()) throw new IllegalArgumentException("Unknown operation");
        return values.getFirst();
    }

    public void drainProgress(String id, String nodeId, int remaining) {
        jdbc.update("UPDATE cluster_node_operation SET remaining=?,operation_state=? WHERE operation_id=? AND node_id=? AND operation_state='RUNNING'",
                remaining, remaining == 0 ? "SUCCEEDED" : "RUNNING", id, nodeId);
    }

    public void expireOperations() {
        var now = databaseTime();
        jdbc.update("UPDATE cluster_node_operation SET operation_state='FAILED',failure='Target instance lost its lease' WHERE operation_state='RUNNING' AND node_id IN (SELECT node_id FROM cluster_access_node WHERE lease_until<=? OR node_state='OFFLINE')", ts(now));
    }

    public Drain resume(String nodeId,long revision,String operationId,String actor) {
        if(operationId==null || !operationId.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid operation ID");
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT lock_id FROM cluster_control_lock WHERE lock_id=1 FOR UPDATE",Integer.class);
            var previous=jdbc.query("SELECT * FROM cluster_node_operation WHERE operation_id=?",JdbcNodeDirectory::drain,operationId);
            if(!previous.isEmpty()) {
                var existing=previous.getFirst();
                if(!existing.type().equals("RESUME") || !existing.nodeId().equals(nodeId) || !existing.requestedBy().equals(actor))
                    throw new IllegalStateException("Operation ID reused");
                return existing;
            }
            var target=lock(nodeId);var now=databaseTime();
            if(!target.leaseUntil().isAfter(now) || !target.lastHeartbeat().plusSeconds(3).isAfter(now) || !target.state().equals("DRAINING") || target.revision()!=revision || activeDrain(nodeId)!=null)
                throw new IllegalStateException("Node is not resumable; active drain must complete first");
            jdbc.update("UPDATE cluster_access_node SET node_state='ACTIVE',revision=revision+1 WHERE node_id=?",nodeId);
            jdbc.update("INSERT INTO cluster_node_operation(operation_id,node_id,requested_by,operation_state,batch_size,deadline,remaining,created_at,failure,operation_type) VALUES (?,?,?,'SUCCEEDED',0,?,?,?,NULL,'RESUME')",
                    operationId,nodeId,actor,ts(now),target.connections(),ts(now));
            return operation(operationId);
        });
    }

    public void offline(String nodeId) {
        jdbc.update("UPDATE cluster_access_node SET node_state='OFFLINE',lease_until=?,revision=revision+1 WHERE node_id=?", ts(databaseTime()), nodeId);
    }

    private static Node node(ResultSet rs, int row) throws SQLException {
        return new Node(rs.getString("node_id"),rs.getString("node_name"),rs.getString("node_state"),instant(rs,"started_at"),
                instant(rs,"heartbeat_at"),instant(rs,"lease_until"),rs.getInt("connections"),rs.getInt("max_connections"),rs.getString("metrics_json"),rs.getLong("revision"));
    }
    private static Drain drain(ResultSet rs, int row) throws SQLException {
        return new Drain(rs.getString("operation_id"),rs.getString("node_id"),rs.getString("requested_by"),rs.getString("operation_state"),rs.getInt("batch_size"),
                instant(rs,"deadline"),rs.getInt("remaining"),instant(rs,"created_at"),rs.getString("failure"),rs.getString("operation_type"));
    }
    private static java.time.LocalDateTime ts(Instant value) {return java.time.LocalDateTime.ofInstant(value,java.time.ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String column) throws SQLException {return rs.getObject(column,java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC);}
}

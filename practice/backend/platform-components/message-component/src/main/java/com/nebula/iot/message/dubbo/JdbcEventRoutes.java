package com.nebula.iot.message.dubbo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.message.SubscriptionSpec;
import com.nebula.iot.message.routing.EventSelector;
import com.nebula.iot.cluster.DatabaseTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Shared SQL control plane and durable outbox/inbox. No database reads for unmatched events. */
public final class JdbcEventRoutes {
    public record Definition(SubscriptionSpec spec, EventSelector selector) { }
    public record Route(String id, Definition definition, String targetNode) { }
    public record Node(String id, String endpoint, String state, Instant drainUntil, long generation, long appliedRevision) { }
    public record Snapshot(long revision, List<Route> routes, List<Node> nodes, Map<String,List<String>> members) { }
    /** A claim carries its fencing token with it; callbacks must never look up a newer token. */
    public record Delivery(String id, String route, String json, int attempts, String ownerToken,String sourceNode) { }
    public record ObservedNode(String id,String endpoint,String state,Instant leaseUntil,long revision,boolean alive) {}
    public record Backlog(long pending,long received,long retrying,Instant oldest) {}
    public record SubscriptionView(String id,String name,String mode,String sourceScope,long generation,List<String> patterns,
            String tenantId,String productId,int deviceCount,List<String> deviceSample,List<String> members,Backlog backlog) {}
    public record SubscriptionPage(long total,int page,List<SubscriptionView> items) {}
    public record DirectoryView(String namespace,long revision,long totalNodes,long activeNodes,long subscriptions,
            Instant sampledAt,List<ObservedNode> nodes,Backlog backlog) {}
    public DirectoryView overview() {
        return overview(null);
    }
    /** Current instances only; null uses explicit JDBC discovery, empty means no discovered instances. */
    public DirectoryView overview(Set<String> discoveredNodes) {
        var time=now();
        var args=new ArrayList<Object>(List.of(namespace,at(time)));
        String current="namespace_id=? AND lease_until>? AND node_state<>'OFFLINE'";
        if(discoveredNodes!=null) {
            if(discoveredNodes.isEmpty())current+=" AND 1=0";
            else {
                current+=" AND node_id IN ("+String.join(",",Collections.nCopies(discoveredNodes.size(),"?"))+")";
                args.addAll(discoveredNodes);
            }
        }
        var nodes=jdbc.query("SELECT node_id,endpoint,node_state,lease_until,applied_revision FROM event_route_node WHERE "+current+" ORDER BY lease_until DESC,node_id LIMIT 100",
                (rs,n)->{
                    var lease=rs.getObject(4,LocalDateTime.class).toInstant(ZoneOffset.UTC);
                    boolean alive=lease.isAfter(time)&&!rs.getString(3).equals("OFFLINE");
                    return new ObservedNode(rs.getString(1),rs.getString(2),alive?rs.getString(3):rs.getString(3).equals("OFFLINE")?"OFFLINE":"UNREACHABLE",lease,rs.getLong(5),alive);
                },args.toArray());
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM event_route_node WHERE "+current,Long.class,args.toArray()));
        return new DirectoryView(namespace,revision(),
                count,count,
                jdbc.queryForObject("SELECT COUNT(*) FROM event_route_subscription WHERE namespace_id=? AND active=true",Long.class,namespace),
                time,nodes,backlog(null));
    }
    private Backlog backlog(String route) {
        var args=route==null?new Object[]{namespace}:new Object[]{namespace,route};
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN delivery_state='PENDING' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN delivery_state='RECEIVED' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN attempts>0 THEN 1 ELSE 0 END),0),MIN(created_at) FROM event_route_delivery WHERE namespace_id=? AND delivery_state IN ('PENDING','RECEIVED')"+(route==null?"":" AND route_id=?"),
                (rs,n)->new Backlog(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getObject(4,LocalDateTime.class)==null?null:rs.getObject(4,LocalDateTime.class).toInstant(ZoneOffset.UTC)),args);
    }
    public SubscriptionPage subscriptions(int page) {
        if(page<0 || page>100000)throw new IllegalArgumentException("Invalid page");
        var items=jdbc.query("SELECT route_id,spec_json,subscription_generation FROM event_route_subscription WHERE namespace_id=? AND active=true ORDER BY subscription_name,route_id LIMIT 20 OFFSET ?",
                (rs,n)->{
                    String id=rs.getString(1);var definition=decode(rs.getString(2));var selector=definition.selector();
                    return new SubscriptionView(id,definition.spec().name(),definition.spec().mode().name(),definition.spec().sourceScope().name(),rs.getLong(3),
                            definition.spec().patterns(),selector.tenantId(),selector.productId(),selector.deviceIds().size(),
                            selector.deviceIds().stream().sorted().limit(20).toList(),
                            jdbc.query("SELECT node_id FROM event_route_member WHERE namespace_id=? AND route_id=? ORDER BY node_id LIMIT 100",(m,i)->m.getString(1),namespace,id),null);
                },namespace,page*20);
        return new SubscriptionPage(jdbc.queryForObject("SELECT COUNT(*) FROM event_route_subscription WHERE namespace_id=? AND active=true",Long.class,namespace),page,items);
    }
    public Backlog subscriptionBacklog(String route) {return backlog(route);}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String namespace;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration nodeLease;
    private final DatabaseTime time;
    public JdbcEventRoutes(JdbcTemplate jdbc, TransactionTemplate tx, String namespace, Duration nodeLease) {
        if (!namespace.matches("[A-Za-z0-9_-]{1,100}") || nodeLease.toSeconds() < 5) throw new IllegalArgumentException("Invalid directory configuration");
        this.jdbc=jdbc; this.tx=tx; this.namespace=namespace; this.nodeLease=nodeLease;
        this.time=new DatabaseTime(jdbc);
    }
    public void initialize() {
        try { jdbc.update("INSERT INTO event_route_namespace(namespace_id,revision) VALUES (?,0)",namespace); }
        catch (DuplicateKeyException existing) { /* Existing namespace. */ }
    }
    private long lock() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT revision FROM event_route_namespace WHERE namespace_id=? FOR UPDATE",Long.class,namespace));
    }
    private void changed() { jdbc.update("UPDATE event_route_namespace SET revision=revision+1 WHERE namespace_id=?",namespace); }
    private Instant now() { return time.now(); }
    private static LocalDateTime at(Instant time) { return LocalDateTime.ofInstant(time,ZoneOffset.UTC); }
    public String register(String node, Definition definition) {
        String target=definition.spec().mode()==SubscriptionSpec.Mode.BROADCAST || definition.spec().sourceScope()==SubscriptionSpec.SourceScope.LOCAL?node:"";
        String logicalKey=definition.spec().name()+":"+definition.spec().mode()+":"+target;
        String encoded=encode(definition);
        String[] selectedRoute={null};
        tx.executeWithoutResult(status->{
            lock();
            var latest=jdbc.query("SELECT route_id,spec_json,active,subscription_generation FROM event_route_subscription WHERE namespace_id=? AND logical_key=? ORDER BY subscription_generation DESC",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getLong(4)),namespace,logicalKey);
            String route;
            long generation;
            if(!latest.isEmpty() && Boolean.TRUE.equals(latest.getFirst().get(2))) {
                route=(String)latest.getFirst().get(0); generation=(Long)latest.getFirst().get(3);
                if(!decode((String)latest.getFirst().get(1)).equals(definition))
                    throw new IllegalStateException("Subscription definition differs; retire before reusing the name: "+definition.spec().name());
            } else {
                generation=latest.isEmpty()?1:(Long)latest.getFirst().get(3)+1;
                route=hash(namespace+"\n"+logicalKey+"\n"+generation);
                jdbc.update("INSERT INTO event_route_subscription(namespace_id,route_id,subscription_name,subscription_mode,target_node,logical_key,subscription_generation,spec_json,source_scope,active) VALUES (?,?,?,?,?,?,?,?,?,true)",namespace,route,definition.spec().name(),definition.spec().mode().name(),target,logicalKey,generation,encoded,definition.spec().sourceScope().name());
            }
            selectedRoute[0]=route;
            var member=jdbc.queryForObject("SELECT COUNT(*) FROM event_route_member WHERE namespace_id=? AND route_id=? AND node_id=?",Long.class,namespace,route,node);
            if(member==0) jdbc.update("INSERT INTO event_route_member(namespace_id,route_id,node_id) VALUES (?,?,?)",namespace,route,node);
            changed();
        });
        return selectedRoute[0];
    }
    public void detach(String node, String route) {
        tx.executeWithoutResult(status->{
            lock();
            jdbc.update("DELETE FROM event_route_member WHERE namespace_id=? AND node_id=? AND route_id=?",namespace,node,route);
            jdbc.update("UPDATE event_route_subscription SET active=false WHERE namespace_id=? AND route_id=? AND target_node<>''",namespace,route);
            changed();
        });
    }
    /** Retire an offline shared subscription explicitly; a stopped worker is not an unsubscribe. */
    public void retireShared(String name) {
        tx.executeWithoutResult(status->{
            lock();
            var routes=jdbc.query("SELECT route_id FROM event_route_subscription WHERE namespace_id=? AND subscription_name=? AND subscription_mode='SHARED' AND target_node=''",(rs,n)->rs.getString(1),namespace,name);
            for(String route:routes) {
                Long live=jdbc.queryForObject("SELECT COUNT(*) FROM event_route_member m LEFT JOIN event_route_node n ON n.namespace_id=m.namespace_id AND n.node_id=m.node_id WHERE m.namespace_id=? AND m.route_id=? AND (n.node_id IS NULL OR n.lease_until>?)",Long.class,namespace,route,at(now()));
                if(live!=0)throw new IllegalStateException("Detach active receivers before retiring subscription");
                jdbc.update("UPDATE event_route_subscription SET active=false WHERE namespace_id=? AND route_id=?",namespace,route);
                jdbc.update("UPDATE event_route_delivery SET delivery_state='CANCELLED',completed_at=?,owner_id=NULL WHERE namespace_id=? AND route_id=? AND delivery_state IN ('PENDING','RECEIVED')",at(now()),namespace,route);
            }
            changed();
        });
    }
    public void join(String node, String endpoint) {
        tx.executeWithoutResult(status->{
            lock();
            // Never replace an existing process identity, even after expiry.
            jdbc.update("INSERT INTO event_route_node(namespace_id,node_id,endpoint,node_state,drain_until,node_generation,lease_until,applied_revision) VALUES (?,?,?,'STARTING',NULL,1,?, -1)",namespace,node,endpoint,at(now().plus(nodeLease)));
            changed();
        });
    }
    public void heartbeat(String node, long appliedRevision) {
        var time=now();
        if(jdbc.update("UPDATE event_route_node SET lease_until=?,applied_revision=? WHERE namespace_id=? AND node_id=? AND node_state IN ('STARTING','READY') AND lease_until>?",at(time.plus(nodeLease)),appliedRevision,namespace,node,at(time))!=1)
            throw new IllegalStateException("Event node lease lost; restart with a new process identity");
    }
    public void markReady(String node,long appliedRevision) {
      tx.executeWithoutResult(status->{
        lock();
        var time=now();
        if(jdbc.update("UPDATE event_route_node SET node_state='READY',drain_until=NULL,applied_revision=?,lease_until=? WHERE namespace_id=? AND node_id=? AND node_state='STARTING' AND lease_until>?",appliedRevision,at(time.plus(nodeLease)),namespace,node,at(time))!=1)
            throw new IllegalStateException("Node cannot become READY after lease loss or drain");
        changed();
      });
    }
    public void beginDrain(String node,Duration timeout) {
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofHours(1))>0)throw new IllegalArgumentException("Invalid drain timeout");
        var time=now();
        if(jdbc.update("UPDATE event_route_node SET node_state='DRAINING',drain_until=?,node_generation=node_generation+1 WHERE namespace_id=? AND node_id=? AND node_state='READY' AND lease_until>?",at(time.plus(timeout)),namespace,node,at(time))!=1)
            throw new IllegalStateException("Node is not READY");
    }
    public void leave(String node) { tx.executeWithoutResult(status->{lock();jdbc.update("UPDATE event_route_node SET node_state='OFFLINE',lease_until=?,node_generation=node_generation+1 WHERE namespace_id=? AND node_id=?",at(Instant.EPOCH),namespace,node);changed();}); }
    public boolean ready(String node) { var time=now();return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM event_route_node WHERE namespace_id=? AND node_id=? AND node_state='READY' AND lease_until>?",Long.class,namespace,node,at(time)))==1; }
    public long revision() { return Objects.requireNonNull(jdbc.queryForObject("SELECT revision FROM event_route_namespace WHERE namespace_id=?",Long.class,namespace)); }
    public Snapshot snapshot(long knownRevision) {
        return tx.execute(status->{
            // Serialize with subscription writes, so revision and contents describe one snapshot.
            long revision=lock(); var time=now();
            var nodes=jdbc.query("SELECT node_id,endpoint,node_state,drain_until,node_generation,applied_revision FROM event_route_node WHERE namespace_id=? AND lease_until>?",(rs,n)->new Node(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,LocalDateTime.class)==null?null:rs.getObject(4,LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getLong(5),rs.getLong(6)),namespace,at(time));
            var routes=revision==knownRevision?null:jdbc.query("SELECT route_id,spec_json,target_node FROM event_route_subscription WHERE namespace_id=? AND active=true",(rs,n)->new Route(rs.getString(1),decode(rs.getString(2)),rs.getString(3)),namespace);
            Map<String,List<String>> members=new HashMap<>();
            jdbc.query("SELECT m.route_id,m.node_id FROM event_route_member m JOIN event_route_node n ON n.namespace_id=m.namespace_id AND n.node_id=m.node_id WHERE m.namespace_id=? AND n.lease_until>?",rs->{members.computeIfAbsent(rs.getString(1),key->new ArrayList<>()).add(rs.getString(2));},namespace,at(time));
            return new Snapshot(revision,routes==null?null:List.copyOf(routes),List.copyOf(nodes),members);
        });
    }
    /** All matching targets commit together; dedupe identity includes the logical subscription. */
    public void enqueue(String sourceNode, String eventIdentity, byte[] event, Set<String> routes, int maximumPending) {
        String encoded=new String(event,StandardCharsets.UTF_8), content=hash(encoded);
        tx.executeWithoutResult(status->{
            lock(); var time=now();
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM event_route_delivery WHERE namespace_id=? AND delivery_state IN ('PENDING','RECEIVED')",Long.class,namespace);
            List<String> missing=new ArrayList<>();
            for(String route:routes) {
                if(jdbc.queryForObject("SELECT COUNT(*) FROM event_route_subscription WHERE namespace_id=? AND route_id=? AND active=true",Long.class,namespace,route)==0)
                    throw new IllegalStateException("Subscription retired while routing; retry with a fresh snapshot");
                String id=hash(route+"\n"+eventIdentity);
                var old=jdbc.query("SELECT content_hash FROM event_route_delivery WHERE namespace_id=? AND delivery_id=?",(rs,n)->rs.getString(1),namespace,id);
                if(old.isEmpty())missing.add(route);
                else if(!content.equals(old.getFirst()))throw new IllegalArgumentException("Event identity reused with different content");
            }
            if(count+missing.size()>maximumPending)throw new IllegalStateException("Event delivery capacity exhausted");
            for(String route:missing)jdbc.update("INSERT INTO event_route_delivery(namespace_id,delivery_id,route_id,event_json,source_node,content_hash,delivery_state,lease_until,available_at,created_at,attempts) VALUES (?,?,?,?,?,?,'PENDING',?,?,?,0)",namespace,hash(route+"\n"+eventIdentity),route,encoded,sourceNode,content,at(Instant.EPOCH),at(time),at(time));
        });
    }
    public List<Delivery> claim(String state, Set<String> routes, String owner, Duration lease, int limit) {
        if(routes.isEmpty())return List.of();
        var time=now();
        String placeholders=String.join(",",Collections.nCopies(routes.size(),"?"));
        List<Object> args=new ArrayList<>(List.of(namespace,state,at(time),at(time)));args.addAll(routes);
        String sender="";
        if(state.equals("PENDING")) {
            sender=" AND (source_node=? OR NOT EXISTS (SELECT 1 FROM event_route_node n WHERE n.namespace_id=event_route_delivery.namespace_id AND n.node_id=event_route_delivery.source_node AND n.lease_until>?))";
            args.add(owner);args.add(at(time));
        }
        if(state.equals("RECEIVED")) {
            // Filter before LIMIT/claim; an ineligible member must never consume or starve eligible work.
            sender=" AND EXISTS (SELECT 1 FROM event_route_subscription s WHERE s.namespace_id=event_route_delivery.namespace_id AND s.route_id=event_route_delivery.route_id AND s.active=true AND (s.target_node='' OR s.target_node=?) AND (s.source_scope='ALL' OR (s.source_scope='LOCAL' AND source_node=?) OR (s.source_scope='REMOTE' AND source_node<>?)))";
            args.add(owner);args.add(owner);args.add(owner);
        }
        args.add(limit);
        var candidates=jdbc.query("SELECT delivery_id,route_id,event_json,attempts,source_node FROM event_route_delivery WHERE namespace_id=? AND delivery_state=? AND lease_until<=? AND available_at<=? AND route_id IN ("+placeholders+")"+sender+" ORDER BY created_at,delivery_id LIMIT ?",(rs,n)->new Delivery(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),null,rs.getString(5)),args.toArray());
        List<Delivery> claimed=new ArrayList<>();
        for(var item:candidates) {
            String token=UUID.randomUUID().toString();
            if(jdbc.update("UPDATE event_route_delivery SET owner_id=?,owner_token=?,lease_until=? WHERE namespace_id=? AND delivery_id=? AND delivery_state=? AND lease_until<=?",owner,token,at(time.plus(lease)),namespace,item.id(),state,at(time))==1)
                claimed.add(new Delivery(item.id(),item.route(),item.json(),item.attempts(),token,item.sourceNode()));
        }
        return claimed;
    }
    public void accept(String id,String route,byte[] event) {
        accept(id,route,event,null);
    }
    public void accept(String id,String route,byte[] event,String receiver) {
        tx.executeWithoutResult(status->{
            if(receiver!=null) {
                Long eligible=jdbc.queryForObject("SELECT COUNT(*) FROM event_route_delivery d JOIN event_route_subscription s ON s.namespace_id=d.namespace_id AND s.route_id=d.route_id WHERE d.namespace_id=? AND d.delivery_id=? AND s.active=true AND (s.target_node='' OR s.target_node=?) AND (s.source_scope='ALL' OR (s.source_scope='LOCAL' AND d.source_node=?) OR (s.source_scope='REMOTE' AND d.source_node<>?))",Long.class,namespace,id,receiver,receiver,receiver);
                if(eligible==null || eligible==0)throw new IllegalStateException("Receiver excluded by subscription source scope");
            }
            var row=jdbc.query("SELECT route_id,content_hash,delivery_state FROM event_route_delivery WHERE namespace_id=? AND delivery_id=? FOR UPDATE",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3)),namespace,id);
            if(row.isEmpty() || !row.getFirst().get(0).equals(route) || !row.getFirst().get(1).equals(hash(new String(event,StandardCharsets.UTF_8))))throw new SecurityException("Unknown or mismatched delivery");
            if(row.getFirst().get(2).equals("PENDING"))jdbc.update("UPDATE event_route_delivery SET delivery_state='RECEIVED',owner_id=NULL,lease_until=?,available_at=? WHERE namespace_id=? AND delivery_id=?",at(Instant.EPOCH),at(now()),namespace,id);
        });
    }
    public void complete(String id,String owner,String token) { jdbc.update("UPDATE event_route_delivery SET delivery_state='DONE',completed_at=?,owner_id=NULL,owner_token=NULL WHERE namespace_id=? AND delivery_id=? AND delivery_state='RECEIVED' AND owner_id=? AND owner_token=?",at(now()),namespace,id,owner,token); }
    public void retry(String id,String owner,String token) {
        jdbc.update("UPDATE event_route_delivery SET attempts=attempts+1,owner_id=NULL,owner_token=NULL,lease_until=?,available_at=? WHERE namespace_id=? AND delivery_id=? AND owner_id=? AND owner_token=?",at(Instant.EPOCH),at(now().plusSeconds(1)),namespace,id,owner,token);
    }
    public long pending() { return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM event_route_delivery WHERE namespace_id=? AND delivery_state IN ('PENDING','RECEIVED')",Long.class,namespace)); }
    public void cleanup(Duration retention) {
        tx.executeWithoutResult(status->{
            lock();
            int expired=jdbc.update("UPDATE event_route_subscription SET active=false WHERE namespace_id=? AND active=true AND target_node<>'' AND target_node IN (SELECT node_id FROM event_route_node WHERE namespace_id=? AND lease_until<=?)",namespace,namespace,at(now()));
            if(expired>0)changed();
            jdbc.update("DELETE FROM event_route_member WHERE namespace_id=? AND node_id IN (SELECT node_id FROM event_route_node WHERE namespace_id=? AND lease_until<=?)",namespace,namespace,at(now()));
        });
        // Broadcast and LOCAL subscriptions belong to a process; cluster shared work survives it.
        jdbc.update("UPDATE event_route_delivery SET delivery_state='CANCELLED',completed_at=?,owner_id=NULL WHERE namespace_id=? AND delivery_state IN ('PENDING','RECEIVED') AND route_id IN (SELECT s.route_id FROM event_route_subscription s WHERE s.namespace_id=? AND s.target_node<>'' AND (s.active=false OR NOT EXISTS (SELECT 1 FROM event_route_node n WHERE n.namespace_id=s.namespace_id AND n.node_id=s.target_node AND n.lease_until>?)))",at(now()),namespace,namespace,at(now()));
        jdbc.update("DELETE FROM event_route_delivery WHERE namespace_id=? AND delivery_state IN ('DONE','CANCELLED') AND completed_at<?",namespace,at(now().minus(retention)));
    }
    private String encode(Definition value) { try { return json.writeValueAsString(value); } catch(Exception e){throw new IllegalArgumentException("Invalid subscription",e);} }
    private Definition decode(String value) { try { return json.readValue(value,Definition.class); } catch(Exception e){throw new IllegalArgumentException("Invalid stored subscription",e);} }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}

package com.nebula.iot.bootstrap.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.cluster.JdbcNodeDirectory;
import com.nebula.iot.gateway.JdbcDeviceSessionDirectory;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.network.mqtt.VertxMqttGateway;
import java.lang.management.ManagementFactory;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Node observations are distinct from readiness of the reliable business data plane. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@RequestMapping("/api/management/access/nodes")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class AccessNodeController {
    public record Node(String nodeId,String name,String protocol,String status,Instant processStartedAt,
                       long processUptimeMillis,VertxMqttGateway.RuntimeSnapshot metrics,
                       String lifecycle,Instant lastHeartbeat,Instant leaseUntil,long revision,boolean fresh) {}
    public record Snapshot(String scope,Instant sampledAt,List<Node> nodes,long totalNodes,Long onlineDevices,boolean complete) {}
    private final AccountDirectory accounts;
    private final ObjectProvider<VertxMqttGateway> gateways;
    private final ObjectProvider<JdbcNodeDirectory> directories;
    private final ObjectProvider<JdbcDeviceSessionDirectory> sessions;
    private final ObjectMapper mapper;
    private final String nodeId=UUID.randomUUID().toString();
    private final String name;

    public AccessNodeController(AccountDirectory accounts,ObjectProvider<VertxMqttGateway> gateways,
            ObjectProvider<JdbcNodeDirectory> directories,ObjectProvider<JdbcDeviceSessionDirectory> sessions,
            ObjectMapper mapper,@Value("${nebula.access.node-name:local-access}") String name) {
        this.accounts=accounts;this.gateways=gateways;this.directories=directories;this.sessions=sessions;this.mapper=mapper;this.name=name;
    }
    public ResponseEntity<Snapshot> snapshot(Principal principal) {
        return snapshot(principal,false);
    }
    @GetMapping
    public ResponseEntity<Snapshot> snapshot(Principal principal,
            @RequestParam(defaultValue="false") boolean includeHistory) {
        accounts.requirePlatformAdmin(principal.getName());
        var directory=directories.getIfAvailable();
        if(directory!=null) {
            var now=directory.databaseTime();
            var recent=now.minusSeconds(120);
            var records=directory.overview(now,recent,includeHistory,200);
            var nodes=records.stream().map(node -> {
                boolean alive=node.leaseUntil().isAfter(now) && !node.state().equals("OFFLINE");
                boolean fresh=alive && node.lastHeartbeat().plusSeconds(3).isAfter(now);
                VertxMqttGateway.RuntimeSnapshot metrics=null;
                if(fresh) {
                    try {metrics=mapper.readValue(node.metricsJson(),VertxMqttGateway.RuntimeSnapshot.class);if(metrics==null)fresh=false;}
                    catch(Exception invalid){fresh=false;}
                }
                String state=alive?node.state():node.state().equals("OFFLINE")?"OFFLINE":"UNREACHABLE";
                return new Node(node.nodeId(),node.name(),"MQTT 5",fresh?"RUNNING":"STOPPED",node.startedAt(),
                        Math.max(0,Duration.between(node.startedAt(),node.lastHeartbeat()).toMillis()),metrics,state,node.lastHeartbeat(),node.leaseUntil(),node.revision(),fresh);
            }).toList();
            long count=directory.overviewSize(now,recent,includeHistory);
            var current=nodes.stream().filter(node -> !node.lifecycle().equals("OFFLINE") &&
                    (node.leaseUntil().isAfter(now)||node.lastHeartbeat().isAfter(recent))).toList();
            boolean complete=directory.overviewSize(now,recent,false)<=current.size() && current.stream().allMatch(Node::fresh);
            return response(new Snapshot("CLUSTER_MEMBERSHIP",now,nodes,count,sessions.getObject().activeCount(),complete));
        }
        var gateway=gateways.getIfAvailable();
        var metrics=gateway==null?null:gateway.runtimeSnapshot();
        var runtime=ManagementFactory.getRuntimeMXBean();
        var node=new Node(nodeId,name,"MQTT 5",metrics==null?"DISABLED":metrics.running()?"RUNNING":"STOPPED",
                Instant.ofEpochMilli(runtime.getStartTime()),runtime.getUptime(),metrics,null,null,null,0,true);
        return response(new Snapshot("LOCAL_PROCESS",Instant.now(),List.of(node),1,metrics==null?null:(long)metrics.onlineDevices(),true));
    }
    private ResponseEntity<Snapshot> response(Snapshot snapshot){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot);}
}

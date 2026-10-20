package com.nebula.iot.bootstrap.message;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.message.dubbo.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Read-only observations; failures never become zero traffic. */
@RestController
@RequestMapping("/api/management/eventbus")
@ConditionalOnExpression("${nebula.runtime.management:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class EventBusMonitorController implements AutoCloseable {
    public record Node(JdbcEventRoutes.ObservedNode directory,String observation,
            DubboEventBus.RuntimeSnapshot metrics,Long lastSuccessAt) {}
    public record Snapshot(String mode,Instant sampledAt,JdbcEventRoutes.DirectoryView directory,
            List<Node> nodes,long collected,boolean complete) {}
    private final AccountDirectory accounts;
    private final ObjectProvider<JdbcEventRoutes> directories;
    private final ObjectProvider<DubboEventBus> buses;
    private final String mode;
    private final ExecutorService probes=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),Thread.ofPlatform().daemon().name("eventbus-monitor-",0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private Snapshot cached;
    private long cachedAt;
    private JdbcEventRoutes.SubscriptionPage cachedPage;
    private long pageAt;
    private String backlogRoute;
    private JdbcEventRoutes.Backlog cachedBacklog;
    private long backlogAt;
    public EventBusMonitorController(AccountDirectory accounts,ObjectProvider<JdbcEventRoutes> directories,
            ObjectProvider<DubboEventBus> buses,@Value("${nebula.eventbus.mode:local}") String mode) {
        this.accounts=accounts;this.directories=directories;this.buses=buses;this.mode=mode;
    }
    @GetMapping
    public ResponseEntity<Snapshot> snapshot(Principal principal) {
        accounts.requirePlatformAdmin(principal.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(collect());
    }
    private synchronized Snapshot collect() {
        if(cached!=null && System.nanoTime()-cachedAt<TimeUnit.SECONDS.toNanos(5))return cached;
        var store=directories.getIfAvailable();var bus=buses.getIfAvailable();
        if(store==null || bus==null)return new Snapshot(mode,Instant.now(),null,List.of(),0,false);
        var directory=bus.discoveredNodeIds().map(store::overview).orElseGet(store::overview);
        Map<String,Long> previous=new HashMap<>();
        if(cached!=null)cached.nodes().forEach(n->{if(n.lastSuccessAt()!=null)previous.put(n.directory().id(),n.lastSuccessAt());});
        var tasks=directory.nodes().stream().<Callable<Node>>map(node->()->{
            if(!node.alive())return new Node(node,node.state(),null,previous.get(node.id()));
            try {
                var metrics=bus.inspectNode(node.id(),node.endpoint());
                if(!metrics.nodeId().equals(node.id()))throw new IllegalStateException("Process identity changed");
                return new Node(node,metrics.healthy()?"AVAILABLE":"DEGRADED",metrics,System.currentTimeMillis());
            } catch(Exception unavailable){return new Node(node,"UNREACHABLE",null,previous.get(node.id()));}
        }).toList();
        List<Node> nodes=new ArrayList<>();
        try {
            var results=probes.invokeAll(tasks,3,TimeUnit.SECONDS);
            for(int i=0;i<results.size();i++) {
                try{nodes.add(results.get(i).get());}
                catch(Exception unavailable){var node=directory.nodes().get(i);nodes.add(new Node(node,node.alive()?"UNREACHABLE":node.state(),null,previous.get(node.id())));}
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("Inspection interrupted",error);}
        catch(RejectedExecutionException busy){
            directory.nodes().forEach(node->nodes.add(new Node(node,node.alive()?"UNAVAILABLE":node.state(),null,previous.get(node.id()))));
        }
        long collected=nodes.stream().filter(n->n.metrics()!=null).count();
        boolean complete=collected==directory.activeNodes();
        cached=new Snapshot(mode,Instant.now(),directory,List.copyOf(nodes),collected,complete);
        cachedAt=System.nanoTime();
        return cached;
    }
    @GetMapping("/subscriptions")
    public synchronized ResponseEntity<JdbcEventRoutes.SubscriptionPage> subscriptions(Principal principal,@RequestParam(defaultValue="0") int page) {
        accounts.requirePlatformAdmin(principal.getName());
        if(page<0 || page>100000)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
        var store=directories.getIfAvailable();
        if(store==null)return ResponseEntity.ok(new JdbcEventRoutes.SubscriptionPage(0,page,List.of()));
        if(cachedPage==null || cachedPage.page()!=page || System.nanoTime()-pageAt>TimeUnit.SECONDS.toNanos(5)) {
            cachedPage=store.subscriptions(page);pageAt=System.nanoTime();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cachedPage);
    }
    @GetMapping("/subscriptions/{route}/backlog")
    public synchronized ResponseEntity<JdbcEventRoutes.Backlog> backlog(Principal principal,@PathVariable String route) {
        accounts.requirePlatformAdmin(principal.getName());
        if(!route.matches("[a-f0-9]{64}"))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
        var store=directories.getIfAvailable();
        if(store==null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!route.equals(backlogRoute)||System.nanoTime()-backlogAt>TimeUnit.SECONDS.toNanos(5)){
            cachedBacklog=store.subscriptionBacklog(route);backlogRoute=route;backlogAt=System.nanoTime();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cachedBacklog);
    }
    @jakarta.annotation.PreDestroy
    @Override public void close(){probes.shutdownNow();}
}

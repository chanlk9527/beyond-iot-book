package com.nebula.iot.message.dubbo;

import com.nebula.iot.message.*;
import com.nebula.iot.message.kafka.EventJsonCodec;
import com.nebula.iot.message.routing.*;
import org.springframework.context.SmartLifecycle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Publisher-side indexed routing with durable, directed Dubbo delivery. */
public final class DubboEventBus implements EventBus, SmartLifecycle, AutoCloseable {
    private record Local(String route, EventSubscriber handler, long revision, CompletableFuture<Void> ready) { }
    private record Device(String tenant, String device) { }
    private record Product(String tenant, String product) { }
    private record Index(long revision, Map<String,JdbcEventRoutes.Route> routes,
                         TopicIndex<String> global, Map<String,TopicIndex<String>> tenants,
                         Map<Product,TopicIndex<String>> products, Map<Device,TopicIndex<String>> devices, Map<String,JdbcEventRoutes.Node> nodes,
                         Map<String,List<String>> members) {
        Set<String> match(EventMessage event,String publisher) {
            Set<String> result=new LinkedHashSet<>(global.match(event.topic()));
            var tenant=tenants.get(event.tenantId());if(tenant!=null)result.addAll(tenant.match(event.topic()));
            var product=products.get(new Product(event.tenantId(),event.productId()));if(product!=null)result.addAll(product.match(event.topic()));
            var device=devices.get(new Device(event.tenantId(),event.deviceId()));if(device!=null)result.addAll(device.match(event.topic()));
            result.removeIf(id->{
                var route=routes.get(id);var scope=route.definition().spec().sourceScope();
                if(!route.definition().selector().matches(event))return true;
                if(!route.targetNode().isEmpty() && !nodes.containsKey(route.targetNode()))return true;
                if(scope==SubscriptionSpec.SourceScope.LOCAL)return !publisher.equals(route.targetNode());
                return scope==SubscriptionSpec.SourceScope.REMOTE && publisher.equals(route.targetNode());
            });
            return result;
        }
    }
    private final JdbcEventRoutes store;
    private final DubboEventTransport transport;
    private final EventNodeDiscovery discovery;
    private final EventJsonCodec codec=new EventJsonCodec();
    private final String namespace,nodeId,host,credential;
    private final int port,maximumPending;
    private final Duration timeout;
    private final ConcurrentMap<String,Local> locals=new ConcurrentHashMap<>();
    private final Set<CompletableFuture<PublishResult>> publishing=ConcurrentHashMap.newKeySet();
    private final AtomicLong remoteAccepted=new AtomicLong(), sent=new AtomicLong();
    private final AtomicLong published=new AtomicLong(),matched=new AtomicLong(),routed=new AtomicLong(),
            completed=new AtomicLong(),failed=new AtomicLong(),handlerNanos=new AtomicLong();
    private final long startedAt=System.currentTimeMillis();
    private volatile String role="unspecified";
    public record RuntimeSnapshot(String nodeId,String role,long sampledAt,long startedAt,boolean healthy,
            long revision,long syncAgeMillis,int subscriptions,long published,long matched,long routed,
            long rpcSent,long rpcReceived,long completed,long failed,long handlerNanos,int activeHandlers,
            int persistenceQueued,int persistenceCapacity) {}
    public void setRole(String role){this.role=role;}
    public RuntimeSnapshot runtimeSnapshot() {
        var current=index;
        return new RuntimeSnapshot(nodeId,role,System.currentTimeMillis(),startedAt,healthy(),
                current==null?-1:current.revision(),synchronizedAt==0?-1:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-synchronizedAt),
                locals.size(),published.get(),matched.get(),routed.get(),sent.get(),remoteAccepted.get(),
                completed.get(),failed.get(),handlerNanos.get(),4-callbackSlots.availablePermits(),
                persistence instanceof ThreadPoolExecutor pool?pool.getQueue().size():0,256);
    }
    /** The SQL address is a diagnostic/legacy value; Nacos mode resolves the process identity. */
    /** Empty Optional denotes explicit JDBC discovery; an empty set denotes no current instances. */
    public Optional<Set<String>> discoveredNodeIds() {
        if(discovery!=null && !discovery.connected())throw new IllegalStateException("EventBus discovery unavailable");
        return discovery==null?Optional.empty():Optional.of(Set.copyOf(discovery.endpoints().keySet()));
    }
    public RuntimeSnapshot inspectNode(String processNode, String legacyEndpoint) {
        String endpoint=discovery==null?legacyEndpoint:discovery.endpoints().get(processNode);
        if(endpoint==null)throw new IllegalStateException("Node is not available in Nacos");
        return inspectNode(endpoint);
    }
    public RuntimeSnapshot inspectNode(String endpoint) {
        if(endpoint.equals(host+":"+port))return runtimeSnapshot();
        String timestamp=Long.toString(System.currentTimeMillis());
        try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                transport.inspect(endpoint,timestamp,signature("inspection",timestamp,new byte[0])),RuntimeSnapshot.class);}
        catch(Exception error){throw new IllegalStateException("Node inspection unavailable",error);}
    }
    private String inspection(String ns,String timestamp,String token) {
        long age=System.currentTimeMillis()-Long.parseLong(timestamp);
        if(!namespace.equals(ns)||age < -30000 || age>30000 || token==null ||
                !MessageDigest.isEqual(signature("inspection",timestamp,new byte[0]).getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("Inspection authentication failed");
        try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(runtimeSnapshot());}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private volatile Index index;
    private volatile long synchronizedAt;
    private volatile boolean running,closed,draining;
    private int activeOperations;
    private ScheduledExecutorService control,relay;
    private ExecutorService callbacks,senders,persistence;
    private final Semaphore callbackSlots=new Semaphore(4);
    private final System.Logger log=System.getLogger(DubboEventBus.class.getName());

    /** Explicit standalone/JDBC discovery constructor, retained for isolated tests and legacy deployments. */
    public DubboEventBus(JdbcEventRoutes store,String namespace,String nodeId,String host,int port,String credential,Duration timeout,int maximumPending) {
        this(store,namespace,nodeId,host,port,credential,timeout,maximumPending,null);
    }
    public DubboEventBus(JdbcEventRoutes store,String namespace,String nodeId,String host,int port,String credential,Duration timeout,int maximumPending,EventNodeDiscovery discovery) {
        this.discovery=discovery;
        if(!namespace.matches("[A-Za-z0-9_-]{1,100}") || !nodeId.matches("[A-Za-z0-9_.-]{1,100}") || host==null || host.equals("0.0.0.0") || !host.matches("[A-Za-z0-9_.-]+") || port<1 || port>65535
                || credential==null || credential.length()<32 || timeout.toMillis()<100 || timeout.toSeconds()>30 || maximumPending<1)
            throw new IllegalArgumentException("Invalid Dubbo event configuration; require unique node, host, port and credential (32+ characters)");
        this.store=store;this.namespace=namespace;this.nodeId=nodeId;this.host=host;this.port=port;this.credential=credential;this.timeout=timeout;this.maximumPending=maximumPending;
        this.transport=new DubboEventTransport(namespace,(int)timeout.toMillis());
        store.initialize();
    }
    @Override public Subscription subscribe(String pattern,EventSubscriber handler) {
        throw new IllegalArgumentException("Dubbo subscriptions require a stable service name");
    }
    @Override public RoutedSubscription subscribe(SubscriptionSpec spec,EventSubscriber handler) { return subscribe(spec,EventSelector.all(),handler); }
    @Override public synchronized RoutedSubscription subscribe(SubscriptionSpec spec,EventSelector selector,EventSubscriber handler) {
        if(closed || draining)throw new IllegalStateException("Event bus closed or draining");
        Objects.requireNonNull(handler);Objects.requireNonNull(selector);
        if(locals.containsKey(spec.name()))throw new IllegalArgumentException("Duplicate subscription name");
        String route=store.register(nodeId,new JdbcEventRoutes.Definition(spec,selector));
        Local local=new Local(route,handler,store.revision(),new CompletableFuture<>());
        locals.put(spec.name(),local);
        return new RoutedSubscription() {
            public CompletionStage<Void> ready(){return local.ready().minimalCompletionStage();}
            public void close(){
                synchronized(DubboEventBus.this){
                    if(locals.get(spec.name())==local) {
                        store.detach(nodeId,route);
                        locals.remove(spec.name(),local);
                        local.ready().completeExceptionally(new CancellationException("Subscription detached"));
                    }
                }
            }
        };
    }
    @Override public synchronized CompletionStage<PublishResult> publish(EventMessage event) {
        try {
            Index current=requireFresh();
            published.incrementAndGet();
            Set<String> targets=current.match(event,nodeId);
            if(!targets.isEmpty()) {
                matched.incrementAndGet();
                var pending=CompletableFuture.supplyAsync(()->{
                    byte[] bytes=codec.encode(event);
                    if(bytes.length>1024*1024)throw new IllegalArgumentException("Event exceeds 1 MiB");
                    // Identity includes trusted tenant/device, not eventId alone.
                    store.enqueue(nodeId,event.tenantId().length()+":"+event.tenantId()+event.deviceId().length()+":"+event.deviceId()+event.eventId(),bytes,targets,maximumPending);
                    routed.addAndGet(targets.size());
                    return new PublishResult(event.eventId(),event.topic(),targets.size(),PublishResult.Confirmation.DURABLY_ROUTED);
                },persistence);
                publishing.add(pending);
                pending.whenComplete((result,error)->publishing.remove(pending));
                if(closed)pending.completeExceptionally(new CancellationException("Event bus closed"));
                return pending;
            }
            return CompletableFuture.completedFuture(new PublishResult(event.eventId(),event.topic(),targets.size(),PublishResult.Confirmation.DURABLY_ROUTED));
        }catch(Exception e){return CompletableFuture.failedFuture(e);}
    }
    private Index requireFresh() {
        if(!healthy())
            throw new IllegalStateException("Event routing snapshot unavailable or stale");
        return index;
    }
    private String accept(String incomingNamespace,String token,String delivery,String route,byte[] event) {
        return accept(incomingNamespace,token,delivery,route,event,true);
    }
    private String[] acceptBatch(String ns,String[] signatures,String[] deliveries,String[] routes,byte[][] events) {
        if(events==null || events.length==0 || events.length>16 || signatures==null || deliveries==null || routes==null
                || signatures.length!=events.length || deliveries.length!=events.length || routes.length!=events.length)
            throw new IllegalArgumentException("Invalid event batch");
        long bytes=0;for(byte[] event:events){if(event==null)throw new IllegalArgumentException("Null event");bytes+=event.length;}
        if(bytes>1024*1024)throw new IllegalArgumentException("Batch exceeds 1 MiB");
        String[] accepted=new String[events.length];
        // Partial acceptance is safe: all identities are durable and retries are deduplicated.
        for(int i=0;i<events.length;i++)accepted[i]=accept(ns,signatures[i],deliveries[i],routes[i],events[i]);
        return accepted;
    }
    private synchronized String accept(String incomingNamespace,String token,String delivery,String route,byte[] event,boolean remote) {
        if(event==null || event.length>1024*1024)throw new IllegalArgumentException("Invalid event size");
        if(!namespace.equals(incomingNamespace) || token==null || !MessageDigest.isEqual(signature(delivery,route,event).getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw new SecurityException("Event RPC authentication failed");
        requireFresh();
        if(locals.values().stream().noneMatch(local->local.route().equals(route)))throw new IllegalStateException("Receiver no longer handles this subscription");
        store.accept(delivery,route,event,nodeId);
        if(remote)remoteAccepted.incrementAndGet();
        return delivery;
    }
    private void synchronize() {
        var old=index;var snapshot=store.snapshot(old==null?-1:old.revision());
        Map<String,JdbcEventRoutes.Node> nodes=new HashMap<>();
        Map<String,String> endpoints=discovery==null?null:discovery.endpoints();
        for(var node:snapshot.nodes()) {
            if(!node.state().equals("READY"))continue;
            String endpoint=endpoints==null?node.endpoint():endpoints.get(node.id());
            if(endpoint!=null)nodes.put(node.id(),new JdbcEventRoutes.Node(node.id(),endpoint,node.state(),node.drainUntil(),node.generation(),node.appliedRevision()));
        }
        if(snapshot.routes()!=null) {
            var global=new TopicIndex<String>();Map<String,TopicIndex<String>> tenants=new HashMap<>();Map<Product,TopicIndex<String>> products=new HashMap<>();Map<Device,TopicIndex<String>> devices=new HashMap<>();
            Map<String,JdbcEventRoutes.Route> routes=new HashMap<>();
            for(var route:snapshot.routes()) {
                routes.put(route.id(),route);var selector=route.definition().selector();List<TopicIndex<String>> indices=new ArrayList<>();
                if(selector.tenantId()==null)indices.add(global);
                else if(selector.deviceIds().isEmpty() && selector.productId()!=null)indices.add(products.computeIfAbsent(new Product(selector.tenantId(),selector.productId()),ignored->new TopicIndex<>()));
                else if(selector.deviceIds().isEmpty())indices.add(tenants.computeIfAbsent(selector.tenantId(),ignored->new TopicIndex<>()));
                else for(String device:selector.deviceIds())indices.add(devices.computeIfAbsent(new Device(selector.tenantId(),device),ignored->new TopicIndex<>()));
                for(var selected:indices)for(String pattern:route.definition().spec().patterns())selected.add(pattern,route.id());
            }
            index=new Index(snapshot.revision(),routes,global,tenants,products,devices,nodes,snapshot.members());
        }else index=new Index(old.revision(),old.routes(),old.global(),old.tenants(),old.products(),old.devices(),nodes,snapshot.members());
        // A failed heartbeat must not extend local readiness or resurrect an expired process.
        store.heartbeat(nodeId,snapshot.revision());
        if(snapshot.nodes().stream().noneMatch(node->node.id().equals(nodeId)&&node.state().equals("READY"))) store.markReady(nodeId,snapshot.revision());
        synchronizedAt=System.nanoTime();
        for(var local:locals.values())if(nodes.containsKey(nodeId) && snapshot.nodes().stream().filter(node->!node.state().equals("DRAINING")).allMatch(node->nodes.containsKey(node.id()) && node.state().equals("READY") && node.appliedRevision()>=Math.max(local.revision(),snapshot.revision())))local.ready().complete(null);
        transport.retainEndpoints(nodes.values().stream().map(JdbcEventRoutes.Node::endpoint).collect(java.util.stream.Collectors.toSet()));
    }
    private void forward() {
        Index current=requireFresh();
        Set<String> routable=new HashSet<>();
        current.routes().forEach((id,route)->{if(!current.members().getOrDefault(id,List.of()).isEmpty())routable.add(id);});
        // At most 16 groups, four bounded sender threads, and enough lease time for four waves.
        var claimed=store.claim("PENDING",routable,nodeId,timeout.multipliedBy(4).plusSeconds(10),16);
        Map<JdbcEventRoutes.Node,List<List<JdbcEventRoutes.Delivery>>> batches=new HashMap<>();
        for(var item:claimed) {
            List<String> members=new ArrayList<>(current.members().getOrDefault(item.route(),List.of()));members.removeIf(id->!current.nodes().containsKey(id));Collections.sort(members);
            var scope=current.routes().get(item.route()).definition().spec().sourceScope();
            members.removeIf(id->scope==SubscriptionSpec.SourceScope.LOCAL ? !id.equals(item.sourceNode()) :
                    scope==SubscriptionSpec.SourceScope.REMOTE && id.equals(item.sourceNode()));
            if(members.isEmpty()){store.retry(item.id(),nodeId,item.ownerToken());continue;}
            var event=codec.decode(item.json().getBytes(StandardCharsets.UTF_8));
            var target=current.nodes().get(members.get(Math.floorMod(Objects.hash(event.tenantId(),event.deviceId()),members.size())));
            var groups=batches.computeIfAbsent(target,ignored->new ArrayList<>());
            if(groups.isEmpty() || groups.getLast().stream().mapToInt(d->d.json().getBytes(StandardCharsets.UTF_8).length).sum()+item.json().getBytes(StandardCharsets.UTF_8).length>1024*1024)groups.add(new ArrayList<>());
            groups.getLast().add(item);
        }
        List<CompletableFuture<Void>> work=new ArrayList<>();
        batches.forEach((target,groups)->groups.forEach(batch->work.add(CompletableFuture.runAsync(()->sendBatch(target,batch),senders))));
        CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
    }
    private void sendBatch(JdbcEventRoutes.Node target,List<JdbcEventRoutes.Delivery> batch) {
        try {
            int size=batch.size();String[] ids=new String[size],routes=new String[size],signatures=new String[size];byte[][] events=new byte[size][];
            for(int i=0;i<size;i++){var item=batch.get(i);ids[i]=item.id();routes[i]=item.route();events[i]=item.json().getBytes(StandardCharsets.UTF_8);signatures[i]=signature(ids[i],routes[i],events[i]);}
            String[] accepted;
            if(target.id().equals(nodeId)) {
                accepted=new String[size];for(int i=0;i<size;i++)accepted[i]=accept(namespace,signatures[i],ids[i],routes[i],events[i],false);
            }else {
                String endpoint=discovery==null?target.endpoint():discovery.endpoints().get(target.id());
                if(endpoint==null)throw new IllegalStateException("Target process is no longer discovered");
                accepted=transport.send(endpoint,signatures,ids,routes,events);
            }
            if(!Arrays.equals(ids,accepted))throw new IllegalStateException("Invalid batch acknowledgement");
            if(!target.id().equals(nodeId))sent.addAndGet(size);
        }catch(Exception e){batch.forEach(item->store.retry(item.id(),nodeId,item.ownerToken()));log.log(System.Logger.Level.WARNING,"Dubbo event batch will retry");}
    }
    private void dispatch() {
        requireFresh();
        if(!callbackSlots.tryAcquire())return;
        Set<String> routes=new HashSet<>();locals.values().forEach(local->routes.add(local.route()));
        try {
            var claimed=store.claim("RECEIVED",routes,nodeId,Duration.ofMinutes(5),1);
            if(claimed.isEmpty()){callbackSlots.release();return;}
            var item=claimed.getFirst();
            callbacks.execute(()->{
                long began=System.nanoTime();
                try {
                    var handler=locals.values().stream().filter(local->local.route().equals(item.route())).findFirst().orElseThrow();
                    var work=handler.handler().onEvent(codec.decode(item.json().getBytes(StandardCharsets.UTF_8))).toCompletableFuture();
                    try{work.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}catch(Exception error){work.cancel(true);throw error;}
                    store.complete(item.id(),nodeId,item.ownerToken());
                    completed.incrementAndGet();
                }catch(Exception e){failed.incrementAndGet();store.retry(item.id(),nodeId,item.ownerToken());log.log(System.Logger.Level.WARNING,"Event handler will retry (delivery "+item.id()+")");}
                finally{handlerNanos.addAndGet(System.nanoTime()-began);callbackSlots.release();}
            });
        }catch(Exception e){callbackSlots.release();throw e;}
    }
    private void guarded(Runnable task) {
        synchronized(this){if(!running || draining)return;activeOperations++;}
        try{task.run();}catch(Exception e){log.log(System.Logger.Level.WARNING,"Dubbo event background operation failed: "+e.getClass().getSimpleName());}
        finally{synchronized(this){activeOperations--;}}
    }
    private String signature(String delivery,String route,byte[] event) {
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(credential.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((namespace+"\n"+delivery+"\n"+route+"\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(event));
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    @Override public synchronized void start() {
        if(running)return;if(closed)throw new IllegalStateException("Event bus closed");
        try {
            transport.start(host,port,new EventDeliveryRpc(){
                public String[] acceptBatch(String ns,String[] tokens,String[] ids,String[] routes,byte[][] events){return DubboEventBus.this.acceptBatch(ns,tokens,ids,routes,events);}
                public String inspect(String ns,String timestamp,String token){return inspection(ns,timestamp,token);}
            });
            if(discovery!=null)discovery.start(nodeId,host,port,role);
            store.join(nodeId,host+":"+port);
            running=true;synchronize();
            control=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("event-route-control").factory());
            relay=Executors.newScheduledThreadPool(2,Thread.ofPlatform().daemon().name("event-route-relay-",0).factory());
            callbacks=Executors.newFixedThreadPool(4,Thread.ofPlatform().daemon().name("event-route-handler-",0).factory());
            senders=Executors.newFixedThreadPool(4,Thread.ofPlatform().daemon().name("event-route-sender-",0).factory());
            persistence=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),Thread.ofPlatform().daemon().name("event-route-persist-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
            control.scheduleWithFixedDelay(()->guarded(this::synchronize),100,500,TimeUnit.MILLISECONDS);
            relay.scheduleWithFixedDelay(()->guarded(this::forward),0,10,TimeUnit.MILLISECONDS);
            relay.scheduleWithFixedDelay(()->guarded(this::dispatch),0,10,TimeUnit.MILLISECONDS);
            control.scheduleWithFixedDelay(()->guarded(()->store.cleanup(Duration.ofDays(7))),1,60,TimeUnit.SECONDS);
        }catch(RuntimeException e){close();throw e;}
    }
    @Override public boolean isRunning(){return running;}
    public boolean healthy(){return running && !draining && index!=null && System.nanoTime()-synchronizedAt<=Duration.ofSeconds(2).toNanos() && (discovery==null || (discovery.connected() && (host+":"+port).equals(discovery.endpoints().get(nodeId))));}
    @Override public int getPhase(){return 50;}
    @Override public boolean supportsCluster(){return true;}
    @Override public boolean supportsDurablePublish(){return true;}
    public long receivedRpcCount(){return remoteAccepted.get();}
    public long sentRpcCount(){return sent.get();}
    public long pendingCount(){return store.pending();}
    @Override public void stop(){close();}
    public synchronized CompletionStage<Void> drain(Duration timeout) {
        if(!running || closed || draining)throw new IllegalStateException("Event bus is not available for drain");
        store.beginDrain(nodeId,timeout);
        draining=true;
        CompletableFuture<Void> result=new CompletableFuture<>();
        Thread.ofVirtual().name("event-route-drain").start(()->{
          try {
            long deadline=System.nanoTime()+timeout.toNanos();
            while(System.nanoTime()<deadline && !drained()) {
                try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
            }
            if(drained()) { close();result.complete(null); }
            else result.completeExceptionally(new TimeoutException("Event node drain timed out"));
          }catch(Exception error){result.completeExceptionally(error);}
        });
        return result;
    }
    private synchronized boolean drained(){return activeOperations==0 && callbackSlots.availablePermits()==4 && publishing.isEmpty();}
    @Override public synchronized void close(){
        if(closed)return;closed=true;running=false;
        if(control!=null)control.shutdownNow();if(relay!=null)relay.shutdownNow();if(callbacks!=null)callbacks.shutdownNow();if(senders!=null)senders.shutdownNow();
        if(persistence!=null)persistence.shutdownNow();
        publishing.forEach(future->future.completeExceptionally(new CancellationException("Event bus closed")));
        locals.values().forEach(local->local.ready().completeExceptionally(new CancellationException("Event bus closed")));
        try{store.leave(nodeId);}finally{try{if(discovery!=null)discovery.close();}finally{transport.close();}}
    }
}

package com.nebula.iot.message.dubbo;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.*;

/** Registers a single process endpoint; never puts business subscriptions or payloads in Nacos. */
public final class NacosEventNodeDiscovery implements EventNodeDiscovery {
    static final String NODE = "eventbus.nodeId", NAMESPACE = "eventbus.namespace";
    private final String service, group, eventNamespace;
    private final Properties properties;
    private final Factory factory;
    private volatile Map<String, String> endpoints = Map.of();
    private volatile NamingService naming;
    private volatile boolean closed;
    private Instance local;
    private EventListener listener;
    @FunctionalInterface interface Factory { NamingService create(Properties properties) throws NacosException; }

    public NacosEventNodeDiscovery(String servers, String nacosNamespace, String group, String service,
                                   String username, String password, String eventNamespace) {
        this(servers, nacosNamespace, group, service, username, password, eventNamespace, NamingFactory::createNamingService);
    }
    NacosEventNodeDiscovery(String servers, String nacosNamespace, String group, String service,
                           String username, String password, String eventNamespace, Factory factory) {
        if (servers == null || servers.isBlank() || group == null || group.isBlank()
                || service == null || service.isBlank() || eventNamespace == null || eventNamespace.isBlank())
            throw new IllegalArgumentException("Nacos server address, group, service and EventBus namespace are required");
        this.service=service; this.group=group; this.eventNamespace=eventNamespace; this.factory=Objects.requireNonNull(factory);
        properties=new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, servers);
        properties.setProperty(PropertyKeyConst.NAMESPACE, Objects.requireNonNullElse(nacosNamespace, ""));
        properties.setProperty(PropertyKeyConst.USERNAME, Objects.requireNonNullElse(username, ""));
        properties.setProperty(PropertyKeyConst.PASSWORD, Objects.requireNonNullElse(password, ""));
        properties.setProperty("namingLoadCacheAtStart", "false");
    }
    @Override public synchronized void start(String nodeId, String host, int port, String role) {
        if (closed || naming != null) throw new IllegalStateException("Nacos discovery already started or closed");
        String stage="create-client";
        try {
            naming=factory.create(properties);
            local=new Instance(); local.setIp(host); local.setPort(port); local.setEphemeral(true);
            local.setMetadata(Map.of(NODE,nodeId,NAMESPACE,eventNamespace,"eventbus.role",role,"eventbus.protocol","dubbo"));
            listener=event -> { if (event instanceof NamingEvent changed) update(changed.getInstances()); };
            stage="register-instance"; naming.registerInstance(service,group,local);
            stage="subscribe"; naming.subscribe(service,group,listener);
            // Registration acknowledgement can precede discovery visibility. Bound the initial barrier.
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
            stage="read-instances";
            do {
                update(naming.getAllInstances(service,group,false));
                if(connected() && (host+":"+port).equals(endpoints.get(nodeId)))break;
                Thread.sleep(100);
            } while(System.nanoTime()<deadline);
            stage="confirm-instance";
            if (!connected() || !(host+":"+port).equals(endpoints.get(nodeId)))
                throw new IllegalStateException("Nacos did not confirm this EventBus instance");
        } catch (Exception error) {
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            String detail=stage+"/"+error.getClass().getSimpleName()+(error instanceof NacosException nacos?"/"+nacos.getErrCode():"");
            close();
            // Client exception messages can contain registry URLs or credentials.
            throw new IllegalStateException("Cannot register EventBus with Nacos ("+detail+"); check server address, namespace and credentials");
        }
    }
    private void update(List<Instance> instances) {
        if (closed) return;
        Map<String,String> next=new HashMap<>(); Set<String> ambiguous=new HashSet<>();
        for (var instance : instances) {
            var metadata=instance.getMetadata();
            if(metadata==null)continue;
            String id=metadata.get(NODE), host=instance.getIp();
            if (!instance.isHealthy() || !instance.isEnabled() || instance.getWeight()<=0
                    || !eventNamespace.equals(metadata.get(NAMESPACE)) || !"dubbo".equals(metadata.get("eventbus.protocol"))
                    || id==null || !id.matches("[A-Za-z0-9_.-]{1,100}")
                    || host==null || host.equals("0.0.0.0") || !host.matches("[A-Za-z0-9_.-]+")
                    || instance.getPort()<1 || instance.getPort()>65535) continue;
            String address=host+":"+instance.getPort();
            String previous=next.putIfAbsent(id,address);
            if (previous!=null && !previous.equals(address)) ambiguous.add(id);
        }
        ambiguous.forEach(next::remove);
        endpoints=Map.copyOf(next);
    }
    @Override public Map<String,String> endpoints() { return closed?Map.of():endpoints; }
    @Override public boolean connected() { var current=naming; return !closed && current!=null && "UP".equals(current.getServerStatus()); }
    @Override public synchronized void close() {
        if (closed) return;
        closed=true; endpoints=Map.of();
        var current=naming;
        if (current==null) return;
        try { if(listener!=null)current.unsubscribe(service,group,listener); } catch (Exception ignored) { }
        try { if(local!=null)current.deregisterInstance(service,group,local); } catch (Exception ignored) { }
        try { current.shutDown(); } catch (Exception ignored) { }
    }
}

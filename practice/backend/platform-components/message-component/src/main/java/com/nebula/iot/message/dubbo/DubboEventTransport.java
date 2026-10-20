package com.nebula.iot.message.dubbo;

import org.apache.dubbo.config.*;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import java.util.*;

/** Direct Dubbo protocol connections; instance addresses come from Nacos in production; subscription routing stays in EventBus. */
public final class DubboEventTransport implements AutoCloseable {
    private final DubboBootstrap bootstrap = DubboBootstrap.newInstance();
    private final Map<String, ReferenceConfig<EventDeliveryRpc>> references = new HashMap<>();
    private final String namespace;
    private final int timeoutMillis;
    public DubboEventTransport(String namespace, int timeoutMillis) {
        this.namespace = namespace;
        this.timeoutMillis = timeoutMillis;
    }
    public void start(String host, int port, EventDeliveryRpc receiver) {
        ApplicationConfig application = new ApplicationConfig("nebula-eventbus");
        application.setQosEnable(false);
        ProtocolConfig protocol = new ProtocolConfig("dubbo", port);
        protocol.setHost(host);
        protocol.setSerialization("fastjson2");
        protocol.setThreads(8);
        protocol.setQueues(64);
        protocol.setPayload(2 * 1024 * 1024);
        ServiceConfig<EventDeliveryRpc> service = new ServiceConfig<>();
        service.setInterface(EventDeliveryRpc.class);
        service.setRef(receiver);
        service.setGroup(namespace);
        service.setVersion("1.0");
        service.setTimeout(timeoutMillis);
        service.setRetries(0);
        service.setRegister(false);
        bootstrap.application(application).registry(new RegistryConfig("N/A")).protocol(protocol).service(service).start();
    }
    public String[] send(String endpoint, String[] signatures, String[] deliveryIds, String[] routeIds, byte[][] events) {
        return reference(endpoint).get().acceptBatch(namespace, signatures, deliveryIds, routeIds, events);
    }
    public String inspect(String endpoint,String timestamp,String signature) {
        return reference(endpoint).get().inspect(namespace,timestamp,signature);
    }
    private synchronized ReferenceConfig<EventDeliveryRpc> reference(String endpoint) {
        return references.computeIfAbsent(endpoint, address -> {
            ReferenceConfig<EventDeliveryRpc> reference = new ReferenceConfig<>();
            reference.setScopeModel(bootstrap.getApplicationModel().getDefaultModule());
            reference.setInterface(EventDeliveryRpc.class);
            reference.setGroup(namespace);
            reference.setVersion("1.0");
            reference.setUrl("dubbo://" + address + "/" + EventDeliveryRpc.class.getName());
            reference.setInjvm(false);
            reference.setCheck(false);
            reference.setRetries(0);
            reference.setTimeout(timeoutMillis);
            reference.setConnections(1);
            return reference;
        });
    }
    public synchronized void retainEndpoints(Set<String> active) {
        references.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            entry.getValue().destroy();
            return true;
        });
    }
    @Override public synchronized void close() {
        references.values().forEach(ReferenceConfig::destroy);
        references.clear();
        bootstrap.destroy();
    }
}

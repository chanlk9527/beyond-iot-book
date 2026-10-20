package com.nebula.iot.message.dubbo;

import java.util.Map;

/** Instance discovery only. Subscription definitions and delivery ownership remain in EventBus. */
public interface EventNodeDiscovery extends AutoCloseable {
    void start(String nodeId, String host, int port, String role);
    Map<String, String> endpoints();
    boolean connected();
    @Override void close();
}

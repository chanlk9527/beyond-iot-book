package com.nebula.iot.message.dubbo;

/** Dubbo binary RPC. Only fixed primitive/string/byte-array arguments cross the wire. */
public interface EventDeliveryRpc {
    String[] acceptBatch(String namespace, String[] signatures, String[] deliveryIds, String[] routeIds, byte[][] events);
    default String inspect(String namespace, String timestamp, String signature) {
        throw new UnsupportedOperationException("Runtime inspection unavailable");
    }
}

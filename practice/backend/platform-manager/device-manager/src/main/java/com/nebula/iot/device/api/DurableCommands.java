package com.nebula.iot.device.api;

import com.nebula.iot.core.command.DeviceDownlink;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.message.DeviceMessage;
import java.util.List;

/** Domain-owned durable command and outbound-work boundary. */
public interface DurableCommands {
    record Target(String nodeId,String sessionId,long epoch) {}
    record Dispatch(DeviceDownlink command,Target target,int attempt) {}
    record Outbound(String eventId,String topic,String tenantId,String deviceId,String messageId,String payload) {}
    default DownlinkStatus enqueue(DeviceDownlink command,String commandId,String kind){return enqueue(command,commandId,kind,"internal");}
    DownlinkStatus enqueue(DeviceDownlink command,String commandId,String kind,String actor);
    DownlinkStatus status(DeviceKey key,String messageId);
    void accept(DeviceMessage message);
    void route(String messageId,Target target);
    Dispatch pending(String messageId);
    boolean startSending(String messageId,Target target,int attempt);
    void reroute(String messageId,Target target,int attempt);
    void delivered(String messageId,boolean confirmed);
    List<Outbound> outgoing(int limit);
    void published(String eventId);
    void sweep();
    void recoverRoutes(java.util.function.Function<DeviceKey,Target> owners);
}

package com.nebula.iot.message.kafka;

import com.nebula.iot.core.message.DeviceMessageBody;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.message.EventMessage;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** A separate JVM used by KafkaEventBusTest; no shared memory with subscribers. */
public final class ForkedEventPublisher {
    public static void main(String[] args) throws Exception {
        var config = Map.<String, Object>of("bootstrap.servers", args[0]);
        try (var bus = new KafkaEventBus(args[1], args[2], "test", "publisher", config, config, Duration.ofSeconds(2))) {
            var event = EventMessage.of("device/d001/property/report",
                    new DeviceMessageBody.PropertyReport(Map.of("temperature", new Value.IntegerValue(40))));
            bus.publish(event).toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }
}

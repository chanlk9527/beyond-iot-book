package com.nebula.iot.bootstrap.message;

import com.nebula.iot.core.message.DeviceMessage;
import com.nebula.iot.gateway.DeviceMessageHandler;
import com.nebula.iot.message.EventBus;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Bridges accepted device messages into the in-process event bus. */
public final class EventPublishingDeviceMessageHandler implements DeviceMessageHandler {
    private final DeviceMessageHandler delegate;
    private final EventBus eventBus;
    private final DeviceMessageEventMapper mapper;
    public EventPublishingDeviceMessageHandler(DeviceMessageHandler delegate, EventBus eventBus) {
        this.delegate = Objects.requireNonNull(delegate); this.eventBus = Objects.requireNonNull(eventBus); this.mapper = new DeviceMessageEventMapper();
    }
    @Override public CompletionStage<Void> handle(DeviceMessage message) {
        return delegate.handle(message).thenCompose(ignored -> eventBus.publish(mapper.map(message))).thenApply(ignored -> null);
    }
}
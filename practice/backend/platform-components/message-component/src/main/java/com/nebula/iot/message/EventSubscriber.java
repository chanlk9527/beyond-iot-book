package com.nebula.iot.message;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface EventSubscriber {
    CompletionStage<Void> onEvent(EventMessage event);
}
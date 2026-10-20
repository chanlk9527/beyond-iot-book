package com.nebula.iot.message.dubbo;

import com.nebula.iot.message.Subscription;
import java.util.concurrent.CompletionStage;

/** Activation is explicit: completion means every live publisher has applied this revision. */
public interface RoutedSubscription extends Subscription {
    CompletionStage<Void> ready();
}

package com.nebula.iot.message;
import java.util.concurrent.CompletionStage;
/** Publish/subscribe boundary; implementations may later route to a cluster broker. */
public interface EventBus {
    Subscription subscribe(String topicPattern, EventSubscriber subscriber);
    default Subscription subscribe(String topicPattern, EventSubscriber subscriber, int priority) { return subscribe(topicPattern, subscriber); }
    CompletionStage<PublishResult> publish(EventMessage event);
    default CompletionStage<PublishResult> publish(String topic, Object payload) { return publish(EventMessage.of(topic, payload)); }
    default boolean supportsCluster() { return false; }
}
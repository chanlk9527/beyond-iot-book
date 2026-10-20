package com.nebula.iot.message;
import java.util.concurrent.CompletionStage;
/** Publish/subscribe boundary; implementations may later route to a cluster broker. */
public interface EventBus {
    Subscription subscribe(String topicPattern, EventSubscriber subscriber);
    default Subscription subscribe(SubscriptionSpec spec, EventSubscriber subscriber) {
        if (spec.patterns().size() != 1) throw new UnsupportedOperationException("Multiple patterns require an implementation override");
        return subscribe(spec.patterns().getFirst(), subscriber);
    }
    default Subscription subscribe(String topicPattern, EventSubscriber subscriber, int priority) { return subscribe(topicPattern, subscriber); }
    CompletionStage<PublishResult> publish(EventMessage event);
    default CompletionStage<PublishResult> publish(String topic, Object payload) { return publish(EventMessage.of(topic, payload)); }
    default boolean supportsCluster() { return false; }
    default boolean supportsDurablePublish() { return false; }
    default Subscription subscribe(SubscriptionSpec spec, com.nebula.iot.message.routing.EventSelector selector, EventSubscriber subscriber) {
        if (!selector.equals(com.nebula.iot.message.routing.EventSelector.all()))
            throw new UnsupportedOperationException("This event bus does not support resource selectors");
        return subscribe(spec, subscriber);
    }
}

package com.nebula.iot.message;

/** Local completion and broker acceptance are intentionally different guarantees. */
public record PublishResult(String eventId, String topic, int subscriberCount, Confirmation confirmation) {
    public enum Confirmation { LOCAL_COMPLETED, BROKER_ACCEPTED }
    public PublishResult(String eventId, String topic, int subscriberCount) {
        this(eventId, topic, subscriberCount, Confirmation.LOCAL_COMPLETED);
    }
}
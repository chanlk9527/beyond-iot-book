package com.nebula.iot.message;

/** Local completion and broker acceptance are intentionally different guarantees. */
public record PublishResult(String eventId, String topic, int subscriberCount, Confirmation confirmation) {
    public enum Confirmation { LOCAL_COMPLETED, BROKER_ACCEPTED, DURABLY_ROUTED }
    public boolean durable() { return confirmation == Confirmation.BROKER_ACCEPTED || confirmation == Confirmation.DURABLY_ROUTED; }
    public PublishResult(String eventId, String topic, int subscriberCount) {
        this(eventId, topic, subscriberCount, Confirmation.LOCAL_COMPLETED);
    }
}

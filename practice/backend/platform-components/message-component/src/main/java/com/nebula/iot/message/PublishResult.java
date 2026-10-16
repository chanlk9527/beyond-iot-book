package com.nebula.iot.message;
public record PublishResult(String eventId,String topic,int subscriberCount) { }
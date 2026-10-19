package com.nebula.iot.device.domain;

import com.nebula.iot.device.api.ProductionViews.*;
import java.util.List;

public interface ProductionRepository {
    List<Batch> batches(String scope, String query, int limit, long offset);
    long count(String scope, String query);
    Batch batch(String id, boolean lock);
    void insert(Batch batch);
    void advance(String id, String status);
    void deliver(String id, String recipient, String reference, long now);
    List<Item> items(String batchId);
    void addItem(String batchId, String deviceId, int sequence);
    void burned(String batchId, String deviceId, long version);
    void tested(String batchId, String deviceId, long version, String result);
    List<Event> events(String batchId, int page);
    long eventCount(String batchId);
    void event(String batchId, Event event);
}

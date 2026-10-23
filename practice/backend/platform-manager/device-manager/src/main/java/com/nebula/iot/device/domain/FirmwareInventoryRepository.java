package com.nebula.iot.device.domain;

public interface FirmwareInventoryRepository {
    java.util.Map<String,FirmwareInventoryDocument> readMany(java.util.List<String> deviceIds);
    FirmwareInventoryDocument read(String scope,String deviceId);
    void save(String scope,String deviceId,FirmwareInventoryDocument document);
    String fingerprint(String deviceId,String messageId,String kind);
    void remember(String deviceId,String messageId,String kind,String fingerprint,long receivedAt);
    void cleanup(long before);
}

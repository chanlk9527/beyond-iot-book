package com.nebula.iot.device.domain;

import java.time.Instant;
import java.util.Map;

/** Info and OTA inventory have independent ordering, based on trusted ingress receipt time. */
public record FirmwareInventoryDocument(Info information,Versions versions,long revision) {
    public record Stamp(String messageId,Instant occurredAt,Instant receivedAt) {
        public boolean newerThan(Stamp other) {
            int order=receivedAt.compareTo(other.receivedAt);
            return order>0 || order==0 && messageId.compareTo(other.messageId)>0;
        }
    }
    public record Info(String firmwareVersion,String hardwareVersion,String bootId,String sdkVersion,boolean clockSynced,Stamp stamp,Instant bootObservedAt) { }
    public record Versions(Map<String,String> values,Stamp stamp) { public Versions { values=Map.copyOf(values); } }
    public static FirmwareInventoryDocument empty() { return new FirmwareInventoryDocument(null,null,0); }
}

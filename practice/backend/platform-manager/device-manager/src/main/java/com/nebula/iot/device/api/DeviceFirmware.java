package com.nebula.iot.device.api;

import java.time.Instant;
import java.util.List;

public final class DeviceFirmware {
    private DeviceFirmware() { }
    public record Information(String hardwareVersion,String bootId,String sdkVersion,boolean clockSynced,
                              Instant occurredAt,Instant receivedAt,Instant bootObservedAt,boolean stale) { }
    public record Component(String component,String version,String source,Instant occurredAt,Instant receivedAt,boolean stale,boolean beforeCurrentBoot) { }
    public record Inventory(long revision,Information information,List<Component> components,Instant checkedAt,long maximumReportAgeSeconds) { }
    public record Check(String code,String message) { }
    public record Preflight(String deviceId,int channel,String component,String currentVersion,String targetVersion,
                            String hardwareVersion,long inventoryRevision,long targetRevision,boolean compatible,
                            List<Check> reasons,List<Check> warnings,Instant checkedAt) { }
}

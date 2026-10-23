package com.nebula.iot.device.api;

import java.util.List;

/** Firmware inventory is independent of model versions and device-reported inventory. */
public final class FirmwareViews {
    private FirmwareViews() { }
    public record Channel(int channel, String component, String name, String versionRule, long createdAt) { }
    public record Artifact(String url, Long size, String sha256, String keyId, String signature, String fileId) {
        public Artifact(String url, Long size, String sha256, String keyId, String signature) { this(url,size,sha256,keyId,signature,null); }
        @Override public String toString() { return "Artifact[size=" + size + ", download credentials redacted]"; }
    }
    public record UploadedFile(String fileId, long size, String sha256, String keyId, boolean signatureVerified) { }
    public record FileCapabilities(boolean enabled, long maximumUploadBytes, List<String> signingKeyIds) { }
    public record Version(String id, int channel, String version, String description,
                          List<String> sourceVersions, List<String> hardwareVersions,
                          Artifact artifact, boolean enabled, long revision, long createdAt) { }
}

package com.nebula.iot.device.domain;

import com.nebula.iot.file.ObjectStorage.Reference;

/** Internal, product-scoped reference; signed download URLs are never persisted. */
public record FirmwareFile(String id, Reference reference, long size, String sha256, String keyId, String signature, long createdAt) {
    @Override public String toString() { return "FirmwareFile[id="+id+", size="+size+"]"; }
}

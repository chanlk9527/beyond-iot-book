package com.nebula.iot.file;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/** Infrastructure only: callers own authorization, object naming and business lifecycle. */
public interface ObjectStorage extends AutoCloseable {
    record Reference(String bucket, String key, String versionId) {
        public Reference {
            if (bucket==null || bucket.isBlank() || key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9/_ .-]{0,1023}")
                    || key.contains("..") || key.endsWith("/") || key.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid object reference");
        }
    }
    record StoredObject(Reference reference, long size, String sha256, String contentType) { }
    record Download(URI url, Instant expiresAt) {
        @Override public String toString() { return "Download[expiresAt="+expiresAt+", URL redacted]"; }
    }
    StoredObject put(String key, StagedUpload upload, String contentType);
    InputStream open(Reference reference);
    void delete(Reference reference);
    Download download(Reference reference, Duration lifetime);
    @Override void close();
}

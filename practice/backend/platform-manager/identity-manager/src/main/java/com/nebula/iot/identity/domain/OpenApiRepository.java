package com.nebula.iot.identity.domain;

import java.time.Instant;
import java.util.Optional;

public interface OpenApiRepository {
    record ApplicationRow(String appId, String name, String secretHash, String scopes, boolean enabled, long revision) {}
    record TokenRow(String appId, String subject, String tenantId, String scopes, Instant expiresAt, boolean revoked, long appRevision) {}
    void insertApplication(String id, String appId, String name, String secretHash, String scopes);
    Optional<ApplicationRow> application(String appId);
    void insertToken(String hash, String appId, String subject, String tenantId, String scopes, Instant expiresAt, long appRevision);
    int setEnabled(String appId, boolean enabled, long revision);
    int rotateSecret(String appId, String secretHash, long revision);
    Optional<TokenRow> token(String hash);
    int revokeToken(String hash);
    String tenantId(String appId);
}

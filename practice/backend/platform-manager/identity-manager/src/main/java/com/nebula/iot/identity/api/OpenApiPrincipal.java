package com.nebula.iot.identity.api;

import java.time.Instant;
import java.util.Set;

/** Verified caller context shared by all externally exposed APIs. */
public record OpenApiPrincipal(String appId, String subject, String tenantId, Set<String> scopes, Instant expiresAt) {
    public OpenApiPrincipal {
        if (appId == null || appId.isBlank() || subject == null || subject.isBlank() || scopes == null || scopes.isEmpty())
            throw new IllegalArgumentException("OpenAPI principal is incomplete");
        scopes = Set.copyOf(scopes);
    }
    public boolean allows(String scope) { return scopes.contains(scope); }
}

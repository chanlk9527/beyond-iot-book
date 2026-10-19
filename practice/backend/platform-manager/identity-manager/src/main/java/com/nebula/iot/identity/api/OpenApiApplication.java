package com.nebula.iot.identity.api;

import java.util.Set;

public record OpenApiApplication(String appId, String name, Set<String> scopes, boolean enabled, long revision) {
    public OpenApiApplication { scopes = Set.copyOf(scopes); }
}

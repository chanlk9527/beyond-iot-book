package com.nebula.iot.identity.domain;

/** Internal persistence projection. Do not return from HTTP endpoints or log. */
public record Account(String id, String username, String passwordHash, boolean enabled, boolean platformAdmin, String tenantId) {
    @Override public String toString() { return "Account[id=" + id + "]"; }
}

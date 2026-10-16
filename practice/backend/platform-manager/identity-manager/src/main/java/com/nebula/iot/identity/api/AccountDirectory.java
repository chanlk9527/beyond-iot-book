package com.nebula.iot.identity.api;

public interface AccountDirectory {
    CurrentAccount requireEnabled(String username);
    void requirePlatformAdmin(String username);
    void requirePermission(String username, String permission);
    String usernameById(String id);
}

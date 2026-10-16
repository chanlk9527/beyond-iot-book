package com.nebula.iot.tenant.api;

public record TenantMember(String accountId, String username, String role, boolean enabled, long revision) {}

package com.nebula.iot.tenant.api;

public record Tenant(String id, String name, boolean enabled, long revision, long memberCount) {}

package com.nebula.iot.tenant.api;

/** Role is a tenant-local label; it does not grant platform-wide privileges. */
public record AccessibleTenant(String id, String name, String role) {}

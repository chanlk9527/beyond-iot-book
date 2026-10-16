package com.nebula.iot.identity.domain;
public final class RbacRows {
    private RbacRows() {}
    public record UserRow(String id,String username,boolean enabled,boolean platformAdmin,long revision,String tenantId) {}
    public record RoleRow(String id,String code,String name,boolean enabled,long revision) {}
}

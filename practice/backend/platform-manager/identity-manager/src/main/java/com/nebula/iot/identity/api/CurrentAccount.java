package com.nebula.iot.identity.api;

/** Safe public projection; never includes authentication material. */
public record CurrentAccount(String id, String username, boolean platformAdmin, java.util.Set<String> permissions, String tenantId, java.util.List<Menus.Navigation> menus) {
    public CurrentAccount(String id,String username,boolean platformAdmin,java.util.Set<String> permissions,String tenantId) {
        this(id,username,platformAdmin,permissions,tenantId,java.util.List.of());
    }
}

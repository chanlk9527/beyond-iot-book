package com.nebula.iot.identity.api;

import java.util.List;
public final class AccessViews {
    private AccessViews() {}
    public record User(String id,String username,boolean enabled,boolean platformAdmin,long revision,List<String> roleIds,String tenantId) {}
    public record Role(String id,String code,String name,boolean enabled,long revision,List<String> permissions) {}
    public record Page<T>(List<T> items,long total,int page,int size) {}
}

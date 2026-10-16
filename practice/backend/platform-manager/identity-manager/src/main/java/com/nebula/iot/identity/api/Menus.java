package com.nebula.iot.identity.api;

import java.util.List;

/** Registered console pages. Details inherit the permission of their list page. */
public final class Menus {
    private Menus() {}
    public record Definition(String code, String name, String group, String path, List<String> permissions, String description, int sortOrder, long revision) {
        public Definition(String code,String name,String group,String path,List<String> permissions,String description) {
            this(code,name,group,path,permissions,description,0,0);
        }
    }
    public record Navigation(String code,String name,String group,String path,int sortOrder) {}
    public static final List<Definition> CATALOG = List.of(
        new Definition("page:tenants", "租户管理", "平台管理", "/platform/tenants", List.of("tenant:read", "tenant:write", "tenant:member:read", "tenant:member:write", "iam:user:write"), "查看、创建和维护租户，管理租户成员"),
        new Definition("page:users", "用户管理", "权限管理", "/access/users", List.of("iam:user:read", "iam:user:write", "iam:role:read", "iam:grant", "tenant:member:write", "tenant:read"), "查看、新建和启停用户，为用户分配角色"),
        new Definition("page:roles", "角色管理", "权限管理", "/access/roles", List.of("iam:role:read", "iam:role:write"), "创建和维护角色，配置角色可访问的页面"),
        new Definition("page:menus", "菜单管理", "权限管理", "/access/menus", List.of("iam:role:read", "iam:role:write"), "查看各页面的授权角色，并调整角色的页面授权"),
        new Definition("page:categories", "产品目录", "产品与设备", "/catalog/:scope/categories", List.of("product:read", "product:write"), "查看和维护产品分类目录"),
        new Definition("page:products", "产品管理", "产品与设备", "/catalog/:scope/products", List.of("product:read", "product:write", "product:publish"), "查看和维护产品、物模型，发布版本及启停产品"),
        new Definition("page:devices", "设备管理", "产品与设备", "/catalog/:scope/devices", List.of("device:read", "device:write", "device:credential", "product:read"), "查看、登记和维护设备，轮换凭证；登记时可选择已有产品")
    );
    public static List<String> permissions(List<String> codes) {
        return CATALOG.stream().filter(menu -> codes.contains(menu.code()))
            .flatMap(menu -> java.util.stream.Stream.concat(java.util.stream.Stream.of(menu.code()), menu.permissions().stream()))
            .distinct().toList();
    }
}

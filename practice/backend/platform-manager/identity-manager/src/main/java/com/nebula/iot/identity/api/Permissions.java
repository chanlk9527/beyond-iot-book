package com.nebula.iot.identity.api;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Registered server-side capabilities. No data-scope or super-admin grant permission. */
public final class Permissions {
    private Permissions() {}
    public record Definition(String code, String name, String group) {}
    public static final List<Definition> CATALOG = List.of(
        new Definition("tenant:read","查看租户与操作记录","租户管理"),
        new Definition("tenant:write","创建、修改及启停租户","租户管理"),
        new Definition("tenant:member:read","查看租户成员","租户管理"),
        new Definition("tenant:member:write","添加、调整及停用租户成员","租户管理"),
        new Definition("iam:user:read","查看用户","权限管理"),
        new Definition("iam:user:write","创建及启停普通用户","权限管理"),
        new Definition("iam:role:read","查看角色与权限点","权限管理"),
        new Definition("iam:role:write","创建、修改及启停角色","权限管理"),
        new Definition("product:read","查看产品与物模型版本","产品与设备"),
        new Definition("product:write","创建及编辑产品草稿","产品与设备"),
        new Definition("product:publish","发布物模型版本及启停产品","产品与设备"),
        new Definition("device:read","查看设备","产品与设备"),
        new Definition("device:write","登记及管理设备生命周期","产品与设备"),
        new Definition("device:credential","轮换设备凭证","产品与设备"),
        new Definition("iam:grant","分配用户角色","权限管理")
    );
    public static final Set<String> ALL = java.util.stream.Stream.concat(
        CATALOG.stream().map(Definition::code), Menus.CATALOG.stream().map(Menus.Definition::code)
    ).collect(Collectors.toUnmodifiableSet());
}

package com.nebula.iot.identity.domain;

import com.nebula.iot.identity.domain.RbacRows.*;
import java.util.List;
public interface RbacRepository {
    List<MenuRow> menus();
    MenuRow menu(String code);
    List<String> menuPermissions(String code);
    void createMenu(MenuRow menu);
    int updateMenu(MenuRow menu);
    int deleteMenu(String code,long revision);
    void clearMenuPermissions(String code);
    void addMenuPermission(String code,String permission);
    List<String> effectivePermissions(String accountId);
    int lock();
    List<UserRow> users(String query,int limit,long offset,String tenantId);
    long userCount(String query,String tenantId);
    UserRow user(String id);
    void createUser(String id,String username,String passwordHash,String tenantId);
    int updateUser(String id,boolean enabled,long revision);
    int touchUser(String id,long revision);
    List<String> userRoles(String id);
    void clearUserRoles(String id);
    void addUserRole(String id,String roleId);
    List<RoleRow> roles();
    RoleRow role(String id);
    List<String> rolePermissions(String id);
    void createRole(String id,String code,String name);
    int updateRole(String id,String name,boolean enabled,long revision);
    void clearPermissions(String id);
    void addPermission(String id,String permission);
    void audit(String id,String actor,String action,String subject);
}

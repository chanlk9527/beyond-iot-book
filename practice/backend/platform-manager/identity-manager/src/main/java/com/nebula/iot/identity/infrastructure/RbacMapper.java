package com.nebula.iot.identity.infrastructure;

import com.nebula.iot.identity.domain.RbacRows.*;
import com.nebula.iot.identity.domain.RbacRepository;
import com.nebula.iot.identity.domain.MenuRow;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface RbacMapper extends RbacRepository {
    @Select("SELECT code,name,group_name AS groupName,path,description,sort_order AS sortOrder,revision FROM identity_menu ORDER BY sort_order,code") List<MenuRow> menus();
    @Select("SELECT code,name,group_name AS groupName,path,description,sort_order AS sortOrder,revision FROM identity_menu WHERE code=#{code}") MenuRow menu(String code);
    @Select("SELECT permission FROM identity_menu_permission WHERE menu_code=#{code} ORDER BY permission") List<String> menuPermissions(String code);
    @Insert("INSERT INTO identity_menu(code,name,group_name,path,description,sort_order) VALUES(#{code},#{name},#{groupName},#{path},#{description},#{sortOrder})") void createMenu(MenuRow menu);
    @Update("UPDATE identity_menu SET name=#{name},group_name=#{groupName},path=#{path},description=#{description},sort_order=#{sortOrder},revision=revision+1 WHERE code=#{code} AND revision=#{revision}") int updateMenu(MenuRow menu);
    @Delete("DELETE FROM identity_menu WHERE code=#{code} AND revision=#{revision}") int deleteMenu(@Param("code") String code,@Param("revision") long revision);
    @Delete("DELETE FROM identity_menu_permission WHERE menu_code=#{code}") void clearMenuPermissions(String code);
    @Insert("INSERT INTO identity_menu_permission(menu_code,permission) VALUES(#{code},#{permission})") void addMenuPermission(@Param("code") String code,@Param("permission") String permission);
    @Select("SELECT DISTINCT p.permission FROM identity_account_role a JOIN identity_role r ON r.id=a.role_id JOIN identity_role_permission p ON p.role_id=r.id WHERE a.account_id=#{accountId} AND r.enabled=TRUE ORDER BY p.permission")
    List<String> effectivePermissions(String accountId);
    @Select("SELECT id FROM identity_security_lock WHERE id=1 FOR UPDATE") int lock();
    @Select("SELECT id,username,enabled,platform_admin AS platformAdmin,revision,tenant_id AS tenantId FROM identity_account WHERE (CAST(#{tenantId} AS VARCHAR) IS NULL OR tenant_id=#{tenantId}) AND POSITION(LOWER(#{query}) IN LOWER(username))>0 ORDER BY username LIMIT #{limit} OFFSET #{offset}")
    List<UserRow> users(@Param("query") String query,@Param("limit") int limit,@Param("offset") long offset,@Param("tenantId") String tenantId);
    @Select("SELECT COUNT(*) FROM identity_account WHERE (CAST(#{tenantId} AS VARCHAR) IS NULL OR tenant_id=#{tenantId}) AND POSITION(LOWER(#{query}) IN LOWER(username))>0") long userCount(@Param("query") String query,@Param("tenantId") String tenantId);
    @Select("SELECT id,username,enabled,platform_admin AS platformAdmin,revision,tenant_id AS tenantId FROM identity_account WHERE id=#{id}") UserRow user(String id);
    @Insert("INSERT INTO identity_account(id,username,password_hash,tenant_id) VALUES(#{id},#{username},#{passwordHash},#{tenantId})")
    void createUser(@Param("id") String id,@Param("username") String username,@Param("passwordHash") String passwordHash,@Param("tenantId") String tenantId);
    @Update("UPDATE identity_account SET enabled=#{enabled},revision=revision+1 WHERE id=#{id} AND revision=#{revision}")
    int updateUser(@Param("id") String id,@Param("enabled") boolean enabled,@Param("revision") long revision);
    @Update("UPDATE identity_account SET revision=revision+1 WHERE id=#{id} AND revision=#{revision}")
    int touchUser(@Param("id") String id,@Param("revision") long revision);
    @Select("SELECT role_id FROM identity_account_role WHERE account_id=#{id} ORDER BY role_id") List<String> userRoles(String id);
    @Delete("DELETE FROM identity_account_role WHERE account_id=#{id}") void clearUserRoles(String id);
    @Insert("INSERT INTO identity_account_role(account_id,role_id) VALUES(#{id},#{roleId})")
    void addUserRole(@Param("id") String id,@Param("roleId") String roleId);
    @Select("SELECT id,code,name,enabled,revision FROM identity_role ORDER BY code") List<RoleRow> roles();
    @Select("SELECT id,code,name,enabled,revision FROM identity_role WHERE id=#{id}") RoleRow role(String id);
    @Select("SELECT permission FROM identity_role_permission WHERE role_id=#{id} ORDER BY permission") List<String> rolePermissions(String id);
    @Insert("INSERT INTO identity_role(id,code,name) VALUES(#{id},#{code},#{name})")
    void createRole(@Param("id") String id,@Param("code") String code,@Param("name") String name);
    @Update("UPDATE identity_role SET name=#{name},enabled=#{enabled},revision=revision+1 WHERE id=#{id} AND revision=#{revision}")
    int updateRole(@Param("id") String id,@Param("name") String name,@Param("enabled") boolean enabled,@Param("revision") long revision);
    @Delete("DELETE FROM identity_role_permission WHERE role_id=#{id}") void clearPermissions(String id);
    @Insert("INSERT INTO identity_role_permission(role_id,permission) VALUES(#{id},#{permission})")
    void addPermission(@Param("id") String id,@Param("permission") String permission);
    @Insert("INSERT INTO identity_audit(id,actor_id,action,subject_id) VALUES(#{id},#{actor},#{action},#{subject})")
    void audit(@Param("id") String id,@Param("actor") String actor,@Param("action") String action,@Param("subject") String subject);
}

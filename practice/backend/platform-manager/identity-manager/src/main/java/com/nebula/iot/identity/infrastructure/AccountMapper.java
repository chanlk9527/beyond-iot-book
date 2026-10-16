package com.nebula.iot.identity.infrastructure;

import com.nebula.iot.identity.domain.Account;
import org.apache.ibatis.annotations.Select;

public interface AccountMapper {
    @Select("SELECT username FROM identity_account WHERE id = #{id}")
    String usernameById(String id);
    @org.apache.ibatis.annotations.Update("UPDATE identity_account SET platform_admin = TRUE WHERE username = #{username} AND enabled = TRUE")
    int grantInitialPlatformAdmin(String username);
    @org.apache.ibatis.annotations.Insert("INSERT INTO identity_account(id, username, password_hash, enabled, platform_admin) VALUES(#{id}, #{username}, #{passwordHash}, TRUE, TRUE)")
    void insert(@org.apache.ibatis.annotations.Param("id") String id,
                @org.apache.ibatis.annotations.Param("username") String username,
                @org.apache.ibatis.annotations.Param("passwordHash") String passwordHash);
    @Select("SELECT id, username, password_hash AS passwordHash, enabled, platform_admin AS platformAdmin, tenant_id AS tenantId FROM identity_account WHERE username = #{username}")
    Account findByUsername(String username);
}

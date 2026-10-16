package com.nebula.iot.tenant.infrastructure;

import com.nebula.iot.tenant.api.*;
import com.nebula.iot.tenant.domain.TenantRepository;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface TenantMapper extends TenantRepository {
    String PROJECTION = "t.id, t.name, t.enabled, t.revision, (SELECT COUNT(*) FROM tenant_membership m WHERE m.tenant_id=t.id AND m.enabled=TRUE) AS memberCount";
    @Select("SELECT " + PROJECTION + " FROM tenant t WHERE POSITION(LOWER(#{query}) IN LOWER(t.name)) > 0 OR POSITION(LOWER(#{query}) IN LOWER(t.id)) > 0 ORDER BY t.id LIMIT #{limit} OFFSET #{offset}")
    List<Tenant> list(@Param("query") String query, @Param("limit") int limit, @Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM tenant WHERE POSITION(LOWER(#{query}) IN LOWER(name)) > 0 OR POSITION(LOWER(#{query}) IN LOWER(id)) > 0")
    long count(String query);
    @Select("SELECT " + PROJECTION + " FROM tenant t WHERE t.id=#{id}")
    Tenant get(String id);
    @Select("SELECT id, name, enabled, revision, 0 AS memberCount FROM tenant WHERE id=#{id} FOR UPDATE")
    Tenant lock(String id);
    @Insert("INSERT INTO tenant(id,name) VALUES(#{id},#{name})")
    void insert(@Param("id") String id, @Param("name") String name);
    @Update("UPDATE tenant SET name=#{name},enabled=#{enabled},revision=revision+1 WHERE id=#{id} AND revision=#{revision}")
    int update(@Param("id") String id, @Param("name") String name, @Param("enabled") boolean enabled, @Param("revision") long revision);
    @Select("SELECT account_id AS accountId, NULL AS username, role_code AS role, enabled, revision FROM tenant_membership WHERE tenant_id=#{id} ORDER BY account_id")
    List<TenantMember> members(String id);
    @Insert("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES(#{id},#{accountId},#{role})")
    void addMember(@Param("id") String id, @Param("accountId") String accountId, @Param("role") String role);
    @Update("UPDATE tenant_membership SET role_code=#{role},enabled=#{enabled},revision=revision+1 WHERE tenant_id=#{id} AND account_id=#{accountId} AND revision=#{revision}")
    int updateMember(@Param("id") String id, @Param("accountId") String accountId, @Param("role") String role, @Param("enabled") boolean enabled, @Param("revision") long revision);
    @Insert("INSERT INTO tenant_audit(id,tenant_id,actor_id,action,subject_id) VALUES(#{id},#{tenantId},#{actorId},#{action},#{subjectId})")
    void audit(@Param("id") String id, @Param("tenantId") String tenantId, @Param("actorId") String actorId, @Param("action") String action, @Param("subjectId") String subjectId);
    @Select("SELECT id,actor_id AS actorId,action,subject_id AS subjectId,occurred_at AS occurredAt FROM tenant_audit WHERE tenant_id=#{id} ORDER BY occurred_at DESC,id DESC LIMIT 50")
    List<TenantAudit> audits(String id);
}

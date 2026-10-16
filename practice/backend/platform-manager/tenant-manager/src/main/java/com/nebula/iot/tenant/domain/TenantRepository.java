package com.nebula.iot.tenant.domain;

import com.nebula.iot.tenant.api.*;
import java.util.List;

public interface TenantRepository {
    List<Tenant> list(String query, int limit, long offset);
    long count(String query);
    Tenant get(String id);
    Tenant lock(String id);
    void insert(String id, String name);
    int update(String id, String name, boolean enabled, long revision);
    List<TenantMember> members(String id);
    void addMember(String id, String accountId, String role);
    int updateMember(String id, String accountId, String role, boolean enabled, long revision);
    void audit(String id, String tenantId, String actorId, String action, String subjectId);
    List<TenantAudit> audits(String id);
}

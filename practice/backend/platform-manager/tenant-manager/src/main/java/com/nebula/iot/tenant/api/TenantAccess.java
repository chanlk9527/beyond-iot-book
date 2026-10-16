package com.nebula.iot.tenant.api;

/** Public boundary for business modules; does not confer platform permissions. */
public interface TenantAccess {
    AccessibleTenant requireMember(String username,String tenantId);
}

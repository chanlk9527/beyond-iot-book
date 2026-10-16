package com.nebula.iot.tenant.infrastructure;

import com.nebula.iot.tenant.api.AccessibleTenant;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface TenantMembershipMapper {
    @Select("""
        SELECT t.id, t.name, m.role_code AS role
          FROM tenant t JOIN tenant_membership m ON m.tenant_id = t.id
         WHERE m.account_id = #{accountId} AND t.enabled = TRUE AND m.enabled = TRUE
         ORDER BY t.id
        """)
    List<AccessibleTenant> findAccessible(String accountId);
}

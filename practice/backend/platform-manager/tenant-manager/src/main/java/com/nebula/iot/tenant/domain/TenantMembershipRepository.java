package com.nebula.iot.tenant.domain;

import com.nebula.iot.tenant.api.AccessibleTenant;
import java.util.List;

public interface TenantMembershipRepository {
    List<AccessibleTenant> findAccessible(String accountId);
}

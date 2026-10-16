package com.nebula.iot.tenant.application;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.AccessibleTenant;
import com.nebula.iot.tenant.domain.TenantMembershipRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Transactional(readOnly = true)
public class TenantMembershipService {
    private final AccountDirectory accounts;
    private final TenantMembershipRepository memberships;
    public TenantMembershipService(AccountDirectory accounts, TenantMembershipRepository memberships) {
        this.accounts = accounts;
        this.memberships = memberships;
    }
    public List<AccessibleTenant> accessible(String username) {
        var account=accounts.requireEnabled(username);
        return memberships.findAccessible(account.id()).stream().filter(t -> t.id().equals(account.tenantId())).toList();
    }
    public AccessibleTenant requireAccessible(String username, String tenantId) {
        return accessible(username).stream().filter(it -> it.id().equals(tenantId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "AccessibleTenant unavailable"));
    }
}

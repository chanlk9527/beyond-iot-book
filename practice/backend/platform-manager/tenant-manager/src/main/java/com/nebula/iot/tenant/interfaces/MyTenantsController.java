package com.nebula.iot.tenant.interfaces;

import com.nebula.iot.tenant.api.AccessibleTenant;
import com.nebula.iot.tenant.application.TenantMembershipService;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class MyTenantsController {
    private final TenantMembershipService memberships;
    public MyTenantsController(TenantMembershipService memberships) { this.memberships = memberships; }
    @GetMapping("/api/management/tenants")
    public List<AccessibleTenant> list(Principal principal) { return memberships.accessible(principal.getName()); }
    @GetMapping("/api/management/tenants/{tenantId}")
    public AccessibleTenant get(Principal principal, @PathVariable String tenantId) {
        return memberships.requireAccessible(principal.getName(), tenantId);
    }
}

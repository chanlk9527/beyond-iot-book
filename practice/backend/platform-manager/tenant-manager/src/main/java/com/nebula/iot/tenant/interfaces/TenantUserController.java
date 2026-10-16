package com.nebula.iot.tenant.interfaces;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.identity.api.AccessViews.User;
import com.nebula.iot.tenant.application.TenantManagementService;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class TenantUserController {
    private final AccountDirectory accounts;
    private final TenantManagementService tenants;
    public TenantUserController(AccountDirectory accounts,TenantManagementService tenants){this.accounts=accounts;this.tenants=tenants;}
    public record CreateUser(String username,String password,String tenantId,String role){
        @Override public String toString(){return "CreateUser[credentials=REDACTED]";}
    }
    @PostMapping("/api/management/iam/users") @ResponseStatus(HttpStatus.CREATED)
    public User create(Principal actor,@RequestBody CreateUser input){
        var account=accounts.requireEnabled(actor.getName());
        String tenantId=input.tenantId()==null?account.tenantId():input.tenantId();
        if(tenantId==null||tenantId.isBlank())throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Tenant is required");
        var member=tenants.createUser(actor.getName(),tenantId,input.username(),input.password(),input.role()==null?"VIEWER":input.role());
        return new User(member.accountId(),member.username(),true,false,0,List.of(),tenantId);
    }
}
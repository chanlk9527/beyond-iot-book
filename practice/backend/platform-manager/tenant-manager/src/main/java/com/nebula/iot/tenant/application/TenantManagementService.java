package com.nebula.iot.tenant.application;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.identity.api.AccountProvisioning;
import com.nebula.iot.tenant.api.*;
import com.nebula.iot.tenant.domain.TenantRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Transactional(readOnly = true)
public class TenantManagementService {
    private final AccountDirectory accounts;
    private final TenantRepository tenants;
    private final AccountProvisioning provisioning;
    public TenantManagementService(AccountDirectory accounts, TenantRepository tenants, AccountProvisioning provisioning) {
        this.accounts = accounts; this.tenants = tenants; this.provisioning = provisioning;
    }
    public record Page(List<Tenant> items, long total, int page, int size) {}
    public Page list(String actor, String query, int page, int size) {
        accounts.requirePermission(actor,"tenant:read");
        if (query == null || query.length() > 100 || page < 1 || size < 1 || size > 100) bad("Invalid pagination or search");
        return new Page(tenants.list(query, size, ((long) page-1)*size), tenants.count(query), page, size);
    }
    public Tenant get(String actor, String id) {
        accounts.requirePermission(actor,"tenant:read");
        return existing(id);
    }
    @Transactional public Tenant create(String actor, String name) {
        accounts.requirePermission(actor,"tenant:write"); validName(name);
        String id = UUID.randomUUID().toString();
        tenants.insert(id,name.strip());
        record(actor,id,"TENANT_CREATED",null);
        return existing(id);
    }
    @Transactional public Tenant update(String actor, String id, String name, Boolean enabled, Long revision) {
        accounts.requirePermission(actor,"tenant:write"); validName(name);
        if (enabled == null || revision == null || revision < 0) bad("Status and revision required");
        existing(id);
        if (tenants.update(id,name.strip(),enabled,revision) != 1) conflict("Tenant changed; refresh and retry");
        record(actor,id,"TENANT_UPDATED",null);
        return existing(id);
    }
    public List<TenantMember> members(String actor, String id) {
        accounts.requirePermission(actor,"tenant:member:read"); requireTenantScope(actor,id); existing(id);
        return tenants.members(id).stream().map(m -> new TenantMember(m.accountId(), accounts.usernameById(m.accountId()), m.role(),m.enabled(),m.revision())).toList();
    }
    @Transactional public void addMember(String actor, String id, String username, String role) {
        accounts.requirePermission(actor,"tenant:member:write"); requireTenantScope(actor,id); validRole(role);
        if (username == null || username.isBlank() || username.length() > 100) bad("Account username required");
        lock(id);
                com.nebula.iot.identity.api.CurrentAccount account;
        try { account = accounts.requireEnabled(username); }
        catch (org.springframework.security.core.AuthenticationException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Account unavailable");
        }
        if (account.platformAdmin() || !id.equals(account.tenantId())) conflict("Account already belongs to another tenant or is a platform administrator");
        if (tenants.members(id).stream().anyMatch(m -> m.accountId().equals(account.id()))) conflict("Membership already exists; edit it instead");
        tenants.addMember(id,account.id(),role);
        record(actor,id,"MEMBER_ADDED",account.id());
    }
    @Transactional public TenantMember createUser(String actor, String id, String username, String password, String role) {
        accounts.requirePermission(actor,"tenant:member:write"); requireTenantScope(actor,id);
        accounts.requirePermission(actor,"iam:user:write");
        validRole(role);
        lock(id);
        if (!existing(id).enabled()) conflict("Enable the tenant before creating users");
        var user = provisioning.createUser(actor,username,password,id);
        tenants.addMember(id,user.id(),role);
        record(actor,id,"MEMBER_USER_CREATED",user.id());
        return new TenantMember(user.id(),user.username(),role,true,0);
    }
    @Transactional public void updateMember(String actor, String id, String accountId, String role, Boolean enabled, Long revision) {
        accounts.requirePermission(actor,"tenant:member:write"); requireTenantScope(actor,id); validRole(role);
        if (enabled == null || revision == null || revision < 0) bad("Status and revision required");
        lock(id);
        if (tenants.updateMember(id,accountId,role,enabled,revision) != 1) conflict("Membership changed or unavailable; refresh and retry");
        record(actor,id,"MEMBER_UPDATED",accountId);
    }
    public List<TenantAudit> audits(String actor, String id) {
        accounts.requirePermission(actor,"tenant:read"); existing(id); return tenants.audits(id);
    }
    private void requireTenantScope(String actor,String id) {
        var account=accounts.requireEnabled(actor);
        if(!account.platformAdmin()&&!id.equals(account.tenantId())) throw new org.springframework.security.access.AccessDeniedException("Tenant unavailable");
    }
    private Tenant existing(String id) {
        var tenant = tenants.get(id);
        if (tenant == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tenant unavailable");
        return tenant;
    }
    private void lock(String id) {
        if (tenants.lock(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tenant unavailable");
    }
    private void record(String actor, String tenant, String action, String subject) {
        tenants.audit(UUID.randomUUID().toString(),tenant,accounts.requireEnabled(actor).id(),action,subject);
    }
    private static void validName(String name) { if (name == null || name.isBlank() || name.length()>100) bad("Tenant name must contain 1 to 100 characters"); }
    private static void validRole(String role) { if (role == null || !Set.of("OWNER","ADMIN","VIEWER").contains(role)) bad("Unknown tenant role"); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
}

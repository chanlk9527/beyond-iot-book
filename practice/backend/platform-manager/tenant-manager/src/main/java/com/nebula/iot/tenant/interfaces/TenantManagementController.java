package com.nebula.iot.tenant.interfaces;

import com.nebula.iot.tenant.api.*;
import com.nebula.iot.tenant.application.TenantManagementService;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/platform/tenants")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class TenantManagementController {
    private final TenantManagementService service;
    public TenantManagementController(TenantManagementService service) { this.service=service; }
    public record CreateTenant(String name) {}
    public record UpdateTenant(String name, Boolean enabled, Long revision) {}
    public record CreateUser(String username, String password, String role) {
        @Override public String toString() { return "CreateUser[credentials=REDACTED]"; }
    }
    public record AddMember(String username, String role) {}
    public record UpdateMember(String role, Boolean enabled, Long revision) {}
    @GetMapping public TenantManagementService.Page list(Principal actor, @RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return service.list(actor.getName(),query,page,size);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Tenant create(Principal actor,@RequestBody CreateTenant input) { return service.create(actor.getName(),input.name()); }
    @GetMapping("/{id}") public Tenant get(Principal actor,@PathVariable String id) { return service.get(actor.getName(),id); }
    @PutMapping("/{id}") public Tenant update(Principal actor,@PathVariable String id,@RequestBody UpdateTenant input) {
        return service.update(actor.getName(),id,input.name(),input.enabled(),input.revision());
    }
    @GetMapping("/{id}/members") public List<TenantMember> members(Principal actor,@PathVariable String id) { return service.members(actor.getName(),id); }
    @PostMapping("/{id}/members") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(Principal actor,@PathVariable String id,@RequestBody AddMember input) { service.addMember(actor.getName(),id,input.username(),input.role()); }
    @PostMapping("/{id}/users") @ResponseStatus(HttpStatus.CREATED)
    public TenantMember createUser(Principal actor,@PathVariable String id,@RequestBody CreateUser input) {
        return service.createUser(actor.getName(),id,input.username(),input.password(),input.role());
    }
    @PutMapping("/{id}/members/{accountId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMember(Principal actor,@PathVariable String id,@PathVariable String accountId,@RequestBody UpdateMember input) {
        service.updateMember(actor.getName(),id,accountId,input.role(),input.enabled(),input.revision());
    }
    @GetMapping("/{id}/audit") public List<TenantAudit> audits(Principal actor,@PathVariable String id) { return service.audits(actor.getName(),id); }
}

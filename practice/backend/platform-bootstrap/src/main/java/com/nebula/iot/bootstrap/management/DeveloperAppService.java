package com.nebula.iot.bootstrap.management;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.identity.application.OpenApiService;
import com.nebula.iot.identity.infrastructure.DeveloperAppMapper;
import com.nebula.iot.identity.infrastructure.DeveloperAppMapper.App;
import com.nebula.iot.tenant.api.TenantAccess;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service @Transactional
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class DeveloperAppService {
    public static final Set<String> SCOPES=Set.of("device:read","device:control","video:watch","video:recording","event:read");
    private final AccountDirectory accounts; private final TenantAccess tenants;
    private final DeveloperAppMapper data; private final OpenApiService core;
    public DeveloperAppService(AccountDirectory accounts,TenantAccess tenants,DeveloperAppMapper data,OpenApiService core){
        this.accounts=accounts;this.tenants=tenants;this.data=data;this.core=core;
    }
    void tenantAdmin(String actor,String tenant){
        if(accounts.requireEnabled(actor).platformAdmin()){tenants.requireMember(actor,tenant);return;}
        var member=tenants.requireMember(actor,tenant);
        if(!Set.of("OWNER","ADMIN").contains(member.role()))throw new AccessDeniedException("Tenant administrator required");
    }
    public List<App> list(String actor,String tenant){tenantAdmin(actor,tenant);return data.tenantApps(tenant);}
    public List<App> reviews(String actor){accounts.requirePlatformAdmin(actor);return data.allApps();}
    public App create(String actor,String tenant,String name,Set<String> scopes){
        tenantAdmin(actor,tenant);String requested=scopes(scopes);String id="app-"+UUID.randomUUID();
        core.register(id,name,scopes);data.profile(id,tenant,requested);data.pending(id);
        audit(actor,id,"CREATED",requested);return data.app(id);
    }
    public App submit(String actor,String tenant,String id,long revision,Set<String> scopes){
        var app=owned(actor,tenant,id,revision);String requested=scopes(scopes);
        if(app.reviewStatus().equals("PENDING"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Application is awaiting review");
        data.submit(id,requested);data.pending(id);audit(actor,id,"SUBMITTED",requested);return data.app(id);
    }
    public App review(String actor,String id,long revision,boolean approved,Set<String> granted,String note){
        accounts.requirePlatformAdmin(actor);App app=locked(id,revision);
        if(!app.reviewStatus().equals("PENDING"))throw new ResponseStatusException(HttpStatus.CONFLICT,"No pending request");
        String selected=approved?scopes(granted):"";
        if(approved&&!new HashSet<>(List.of(app.requestedScopes().split(" "))).containsAll(granted))throw new IllegalArgumentException("Grant exceeds application request");
        if(note==null||note.length()>1000||(!approved&&note.isBlank()))throw new IllegalArgumentException("Review note required");
        data.review(id,approved?"APPROVED":"REJECTED",note);data.approve(id,approved,selected);
        audit(actor,id,approved?"APPROVED":"REJECTED",note+ (selected.isEmpty()?"":"; "+selected));return data.app(id);
    }
    public String secret(String actor,String tenant,String id,long revision){
        App app=owned(actor,tenant,id,revision);
        if(!app.reviewStatus().equals("APPROVED")||!app.enabled())throw new ResponseStatusException(HttpStatus.CONFLICT,"Approved enabled application required");
        String secret=core.rotateSecret(id,revision);audit(actor,id,"SECRET_ROTATED","");return secret;
    }
    public App status(String actor,String id,long revision,boolean enabled){
        accounts.requirePlatformAdmin(actor);App app=locked(id,revision);
        if(enabled&&!app.reviewStatus().equals("APPROVED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval required");
        core.setEnabled(id,enabled,revision);audit(actor,id,enabled?"ENABLED":"DISABLED","");return data.app(id);
    }
    public List<DeveloperAppMapper.Audit> history(String actor,String tenant,String id){
        tenantAdmin(actor,tenant);App app=data.app(id);
        if(app==null||!app.tenantId().equals(tenant))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return data.auditRecords(id);
    }
    public List<DeveloperAppMapper.Audit> platformHistory(String actor,String id){
        accounts.requirePlatformAdmin(actor);
        if(data.app(id)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return data.auditRecords(id);
    }
    App owned(String actor,String tenant,String id,long revision){
        tenantAdmin(actor,tenant);App app=data.app(id);
        if(app==null||!app.tenantId().equals(tenant))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return locked(id,revision);
    }
    App locked(String id,long revision){
        if(data.lock(id)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        App app=data.app(id);if(app==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(app.revision()!=revision)throw new ResponseStatusException(HttpStatus.CONFLICT,"Application revision changed");return app;
    }
    static String scopes(Set<String> scopes){
        if(scopes==null||scopes.isEmpty()||scopes.stream().anyMatch(Objects::isNull)||!SCOPES.containsAll(scopes))throw new IllegalArgumentException("Invalid scopes");
        return String.join(" ",new TreeSet<>(scopes));
    }
    void audit(String actor,String id,String action,String detail){
        if(detail.length()>1000)detail=detail.substring(0,1000);
        data.audit(UUID.randomUUID().toString(),id,actor,action,detail);
    }
}

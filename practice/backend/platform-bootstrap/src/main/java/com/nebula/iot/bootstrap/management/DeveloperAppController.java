package com.nebula.iot.bootstrap.management;

import com.nebula.iot.identity.infrastructure.DeveloperAppMapper;
import com.nebula.iot.identity.interfaces.OpenApiController.Secret;
import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController @RequestMapping("/api/management/developer")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class DeveloperAppController {
    public record Create(String name,Set<String> scopes){}
    public record Submit(long revision,Set<String> scopes){}
    public record Review(long revision,boolean approved,Set<String> scopes,String note){}
    public record Revision(long revision){}
    public record Status(long revision,boolean enabled){}
    private final DeveloperAppService service;
    public DeveloperAppController(DeveloperAppService service){this.service=service;}
    @GetMapping("/tenants/{tenantId}/apps") public List<DeveloperAppMapper.App> list(Principal p,@PathVariable String tenantId){return service.list(p.getName(),tenantId);}
    @PostMapping("/tenants/{tenantId}/apps") @ResponseStatus(HttpStatus.CREATED)
    public DeveloperAppMapper.App create(Principal p,@PathVariable String tenantId,@RequestBody Create input){return service.create(p.getName(),tenantId,input.name(),input.scopes());}
    @PostMapping("/tenants/{tenantId}/apps/{appId}/submit")
    public DeveloperAppMapper.App submit(Principal p,@PathVariable String tenantId,@PathVariable String appId,@RequestBody Submit input){return service.submit(p.getName(),tenantId,appId,input.revision(),input.scopes());}
    @PostMapping("/tenants/{tenantId}/apps/{appId}/secret")
    public ResponseEntity<Secret> secret(Principal p,@PathVariable String tenantId,@PathVariable String appId,@RequestBody Revision input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Secret(service.secret(p.getName(),tenantId,appId,input.revision())));}
    @GetMapping("/tenants/{tenantId}/apps/{appId}/audit")
    public List<DeveloperAppMapper.Audit> audit(Principal p,@PathVariable String tenantId,@PathVariable String appId){return service.history(p.getName(),tenantId,appId);}
    @GetMapping("/reviews") public List<DeveloperAppMapper.App> reviews(Principal p){return service.reviews(p.getName());}
    @GetMapping("/reviews/{appId}/audit") public List<DeveloperAppMapper.Audit> history(Principal p,@PathVariable String appId){return service.platformHistory(p.getName(),appId);}
    @PostMapping("/reviews/{appId}")
    public DeveloperAppMapper.App review(Principal p,@PathVariable String appId,@RequestBody Review input){return service.review(p.getName(),appId,input.revision(),input.approved(),input.scopes(),input.note());}
    @PutMapping("/reviews/{appId}/status")
    public DeveloperAppMapper.App status(Principal p,@PathVariable String appId,@RequestBody Status input){return service.status(p.getName(),appId,input.revision(),input.enabled());}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(){return Map.of("error","invalid_request");}
}

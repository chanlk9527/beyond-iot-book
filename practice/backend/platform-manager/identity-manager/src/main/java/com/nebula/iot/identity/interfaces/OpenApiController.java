package com.nebula.iot.identity.interfaces;

import com.nebula.iot.identity.application.OpenApiService;
import com.nebula.iot.identity.api.AccountDirectory;
import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class OpenApiController {
    public record Register(String appId,String name,Set<String> scopes) {}
    public record TokenRequest(String appId,String clientSecret,String subject,String tenantId,Set<String> scopes) {
        @Override public String toString(){return "TokenRequest[credentials=REDACTED]";}
    }
    public record Status(boolean enabled,long revision) {}
    public record Rotate(long revision) {}
    public record Secret(String clientSecret) { @Override public String toString(){return "Secret[REDACTED]";} }
    private final OpenApiService service;
    private final AccountDirectory accounts;
    public OpenApiController(OpenApiService service, AccountDirectory accounts){this.service=service;this.accounts=accounts;}
    @PostMapping("/api/management/openapi/apps")
    public ResponseEntity<OpenApiService.Registration> register(Principal principal,@RequestBody Register input){
        if(principal==null)throw new org.springframework.security.access.AccessDeniedException("Login required");
        accounts.requirePlatformAdmin(principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.register(input.appId(),input.name(),input.scopes()));
    }
    @PostMapping("/api/open/v1/oauth/token")
    public OpenApiService.Token token(@RequestBody TokenRequest input){return service.issue(input.appId(),input.clientSecret(),input.subject(),input.tenantId(),input.scopes());}
    @PostMapping("/api/open/v1/oauth/revoke") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestHeader("Authorization") String authorization){service.revoke(bearer(authorization));}
    @GetMapping("/api/open/v1/me")
    public com.nebula.iot.identity.api.OpenApiPrincipal current(org.springframework.security.core.Authentication authentication){
        return (com.nebula.iot.identity.api.OpenApiPrincipal)authentication.getPrincipal();
    }
    @PutMapping("/api/management/openapi/apps/{appId}/status") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(Principal principal,@PathVariable String appId,@RequestBody Status input){
        accounts.requirePlatformAdmin(principal.getName());service.setEnabled(appId,input.enabled(),input.revision());
    }
    @PostMapping("/api/management/openapi/apps/{appId}/secret")
    public ResponseEntity<Secret> rotate(Principal principal,@PathVariable String appId,@RequestBody Rotate input){
        accounts.requirePlatformAdmin(principal.getName());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Secret(service.rotateSecret(appId,input.revision())));
    }
    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String,String> invalidCredentials(){return Map.of("error","invalid_client_or_token");}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalidRequest(){return Map.of("error","invalid_request");}
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String,String> forbidden(){return Map.of("error","insufficient_permission");}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> duplicate(){return Map.of("error","application_already_exists");}
    private static String bearer(String value){if(value==null||!value.startsWith("Bearer "))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");return value.substring(7);}
}

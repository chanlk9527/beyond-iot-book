package com.nebula.iot.device.interfaces;

import com.nebula.iot.device.api.ProductHistoryPolicy;
import com.nebula.iot.device.application.DeviceHistoryService;
import com.nebula.iot.storage.HistoryStore;
import com.nebula.iot.storage.HistoryStorageException;
import java.security.Principal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnExpression("${nebula.runtime.management:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@RequestMapping({"/api/management/catalog/platform","/api/management/catalog/tenants/{tenantId}"})
public class DeviceHistoryController {
    private final DeviceHistoryService service;
    public DeviceHistoryController(DeviceHistoryService service) {this.service=service;}
    public record PolicyInput(long revision,ProductHistoryPolicy policy) {}
    @GetMapping("/products/{id}/data-policy")
    public DeviceHistoryService.PolicyView policy(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id) {
        return service.policy(actor.getName(),tenantId,id);
    }
    @PutMapping("/products/{id}/data-policy")
    public DeviceHistoryService.PolicyView save(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@RequestBody PolicyInput input) {
        return service.savePolicy(actor.getName(),tenantId,id,input.revision(),input.policy());
    }
    @GetMapping("/devices/{id}/history/{kind}/{identifier}")
    public DeviceHistoryService.HistoryPage history(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,
            @PathVariable HistoryStore.Kind kind,@PathVariable String identifier,@RequestParam Instant from,@RequestParam Instant to,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return service.query(actor.getName(),tenantId,id,kind,identifier,from,to,cursor,limit);
    }
    @ExceptionHandler(HistoryStorageException.class)
    org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> storageUnavailable() {
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"History storage is unavailable"));
    }
}

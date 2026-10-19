package com.nebula.iot.device.interfaces;

import com.nebula.iot.device.api.ProductionViews.*;
import com.nebula.iot.device.api.CatalogViews.Page;
import com.nebula.iot.device.application.ProductionService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnExpression("${nebula.runtime.management:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@RequestMapping({"/api/management/catalog/platform/production/batches","/api/management/catalog/tenants/{tenantId}/production/batches"})
public class ProductionController {
    private final ProductionService service;
    public ProductionController(ProductionService service) { this.service=service; }
    public record Revision(Long revision) { }
    public record Evidence(Long revision,Long credentialVersion,String result,String station,String note) { }
    public record Release(Long revision,String note) { }
    public record Delivery(Long revision,String recipient,String deliveryRef,String note) { }
    @GetMapping public Page<Batch> list(Principal actor,@PathVariable(required=false) String tenantId,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(actor.getName(),tenantId,query,page,size);
    }
    @GetMapping("/{id}") public Detail detail(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@RequestParam(defaultValue="1") int eventPage) {
        return service.detail(actor.getName(),tenantId,id,eventPage);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Detail create(Principal actor,@PathVariable(required=false) String tenantId,@RequestBody ProductionService.Create body) {
        return service.create(actor.getName(),tenantId,body);
    }
    @PostMapping("/{id}/generate") public Detail generate(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@RequestBody Revision body) {
        return service.generate(actor.getName(),tenantId,id,body.revision());
    }
    @PostMapping("/{id}/credentials") public List<ProvisioningCredential> credentials(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@RequestBody Revision body,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return service.credentials(actor.getName(),tenantId,id,body.revision());
    }
    @PostMapping("/{id}/devices/{deviceId}/burn") public Detail burn(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@PathVariable String deviceId,@RequestBody Evidence body) {
        return service.burn(actor.getName(),tenantId,id,deviceId,body.revision(),body.credentialVersion(),body.station(),body.note());
    }
    @PostMapping("/{id}/devices/{deviceId}/tests") public Detail test(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@PathVariable String deviceId,@RequestBody Evidence body) {
        return service.test(actor.getName(),tenantId,id,deviceId,body.revision(),body.credentialVersion(),body.result(),body.station(),body.note());
    }
    @PostMapping("/{id}/release") public Detail release(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@RequestBody Release body) {
        return service.release(actor.getName(),tenantId,id,body.revision(),body.note());
    }
    @PostMapping("/{id}/deliver") public Detail deliver(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@RequestBody Delivery body) {
        return service.deliver(actor.getName(),tenantId,id,body.revision(),body.recipient(),body.deliveryRef(),body.note());
    }
}

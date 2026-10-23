package com.nebula.iot.device.interfaces;

import com.nebula.iot.device.api.CatalogViews.Page;
import com.nebula.iot.device.api.FirmwareViews.*;
import com.nebula.iot.device.application.FirmwareService;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnExpression("${nebula.runtime.management:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@RequestMapping({"/api/management/catalog/platform/products/{productId}/firmware/channels", "/api/management/catalog/tenants/{tenantId}/products/{productId}/firmware/channels"})
public class FirmwareController {
    private final FirmwareService service;
    public FirmwareController(FirmwareService service) { this.service=service; }
    public record Status(Boolean enabled,Long revision) { }
    @GetMapping public List<Channel> channels(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String productId) {
        return service.channels(actor.getName(),tenantId,productId);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Channel createChannel(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String productId,@RequestBody FirmwareService.CreateChannel input) {
        return service.createChannel(actor.getName(),tenantId,productId,input);
    }
    @GetMapping("/{channel}/versions") public Page<Version> versions(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String productId,@PathVariable int channel,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return service.versions(actor.getName(),tenantId,productId,channel,page,size);
    }
    @PostMapping("/{channel}/versions") @ResponseStatus(HttpStatus.CREATED)
    public Version createVersion(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String productId,@PathVariable int channel,@RequestBody FirmwareService.CreateVersion input) {
        return service.createVersion(actor.getName(),tenantId,productId,channel,input);
    }
    @PutMapping("/{channel}/versions/{id}/status") public Version status(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String productId,@PathVariable int channel,@PathVariable String id,@RequestBody Status input) {
        return service.status(actor.getName(),tenantId,productId,channel,id,input.enabled(),input.revision());
    }
}

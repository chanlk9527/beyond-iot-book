package com.nebula.iot.device.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.nebula.iot.device.api.DeviceShadow;
import com.nebula.iot.device.application.DeviceShadowService;
import java.security.Principal;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@RequestMapping({"/api/management/catalog/platform/devices/{id}/shadow",
                 "/api/management/catalog/tenants/{tenantId}/devices/{id}/shadow"})
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class DeviceShadowController {
    private final DeviceShadowService service;
    public DeviceShadowController(DeviceShadowService service) { this.service = service; }
    public record DesiredPatch(Long version, Map<String, JsonNode> desired) {}
    @GetMapping
    public DeviceShadow get(Principal actor, @PathVariable(required = false) String tenantId, @PathVariable String id,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.read(actor.getName(), tenantId, id);
    }
    @PutMapping("/desired")
    public DeviceShadow desired(Principal actor, @PathVariable(required = false) String tenantId, @PathVariable String id,
            @RequestBody DesiredPatch input, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.updateDesired(actor.getName(), tenantId, id, input.version(), input.desired());
    }
}


package com.nebula.iot.device.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.nebula.iot.device.api.*;
import com.nebula.iot.device.application.*;
import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@RequestMapping({"/api/management/catalog/platform/devices/{id}",
        "/api/management/catalog/tenants/{tenantId}/devices/{id}"})
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class DeviceCommandController {
    private final DeviceCommandService commands;
    private final DeviceShadowService shadows;
    public DeviceCommandController(DeviceCommandService commands, DeviceShadowService shadows) {
        this.commands = commands; this.shadows = shadows;
    }
    public record OperationInput(String operationId, Map<String, JsonNode> inputs) {}
    public record PropertiesInput(Map<String, JsonNode> properties) {}
    public record DesiredDispatch(DeviceShadow shadow, DownlinkStatus downlink, String dispatchError) {}

    @PostMapping("/downlinks/operations")
    public DownlinkStatus invoke(Principal actor, @PathVariable(required = false) String tenantId,
            @PathVariable String id, @RequestBody OperationInput input) {
        return commands.invoke(actor.getName(), tenantId, id, input.operationId(), input.inputs());
    }
    @PostMapping("/downlinks/properties")
    public DownlinkStatus write(Principal actor, @PathVariable(required = false) String tenantId,
            @PathVariable String id, @RequestBody PropertiesInput input) {
        return commands.write(actor.getName(), tenantId, id, input.properties());
    }
    @GetMapping("/downlinks/{messageId}")
    public DownlinkStatus status(Principal actor, @PathVariable(required = false) String tenantId,
            @PathVariable String id, @PathVariable String messageId, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return commands.get(actor.getName(), tenantId, id, messageId);
    }
    @PutMapping("/shadow/desired-and-dispatch")
    public DesiredDispatch desired(Principal actor, @PathVariable(required = false) String tenantId,
            @PathVariable String id, @RequestBody DeviceShadowController.DesiredPatch input,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var shadow = shadows.updateDesired(actor.getName(), tenantId, id, input.version(), input.desired());
        // Removing a desired target only changes platform state; null is never sent as a device value.
        var properties = new HashMap<String, JsonNode>();
        input.desired().forEach((key, value) -> { if (value != null && !value.isNull()) properties.put(key, value); });
        if (properties.isEmpty()) return new DesiredDispatch(shadow, null, null);
        try { return new DesiredDispatch(shadow, commands.write(actor.getName(), tenantId, id, properties), null); }
        catch (ResponseStatusException e) {
            // Saving and sending are not atomic. Report the committed shadow even when dispatch is rejected.
            return new DesiredDispatch(shadow, null, e.getReason());
        }
    }
}

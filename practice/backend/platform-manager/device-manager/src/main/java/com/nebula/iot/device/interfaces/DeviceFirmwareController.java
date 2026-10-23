package com.nebula.iot.device.interfaces;

import com.nebula.iot.device.api.DeviceFirmware.*;
import com.nebula.iot.device.application.DeviceFirmwareService;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnExpression("${nebula.runtime.management:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@RequestMapping({"/api/management/catalog/platform/devices/{id}/firmware","/api/management/catalog/tenants/{tenantId}/devices/{id}/firmware"})
public class DeviceFirmwareController {
    private final DeviceFirmwareService service;
    public DeviceFirmwareController(DeviceFirmwareService service) { this.service=service; }
    @GetMapping public Inventory inventory(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id) {
        return service.inventory(actor.getName(),tenantId,id);
    }
    @GetMapping("/channels/{channel}/versions/{versionId}/preflight")
    public Preflight preflight(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String id,@PathVariable int channel,@PathVariable String versionId) {
        return service.preflight(actor.getName(),tenantId,id,channel,versionId);
    }
}

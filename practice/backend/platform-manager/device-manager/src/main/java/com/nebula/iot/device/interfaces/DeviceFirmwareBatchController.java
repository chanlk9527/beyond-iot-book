package com.nebula.iot.device.interfaces;

import com.nebula.iot.device.api.DeviceFirmware.Inventory;
import com.nebula.iot.device.application.DeviceFirmwareService;
import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnExpression("${nebula.runtime.management:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@RequestMapping({"/api/management/catalog/platform/devices/firmware-inventory","/api/management/catalog/tenants/{tenantId}/devices/firmware-inventory"})
public class DeviceFirmwareBatchController {
    private final DeviceFirmwareService service;
    public DeviceFirmwareBatchController(DeviceFirmwareService service) { this.service=service; }
    @GetMapping public Map<String,Inventory> inventories(Principal actor,@PathVariable(required=false) String tenantId,@RequestParam List<String> ids) {
        return service.inventories(actor.getName(),tenantId,ids);
    }
}

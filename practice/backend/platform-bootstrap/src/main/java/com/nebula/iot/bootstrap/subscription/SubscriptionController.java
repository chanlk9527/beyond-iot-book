package com.nebula.iot.bootstrap.subscription;

import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.network.mqtt.VertxMqttGateway;
import jakarta.servlet.http.*;
import java.security.Principal;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/realtime")
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class SubscriptionController {
    private final SubscriptionGateway gateway;
    private final DevicePresence presence;
    private final CatalogService catalog;
    private final ObjectProvider<VertxMqttGateway> mqtt;
    public SubscriptionController(SubscriptionGateway gateway, DevicePresence presence, CatalogService catalog, ObjectProvider<VertxMqttGateway> mqtt) {
        this.gateway = gateway; this.presence = presence; this.catalog = catalog; this.mqtt = mqtt;
    }
    public record Request(List<SubscriptionGateway.Resource> devices) { }
    public record Presence(String state) { }
    @PostMapping("/tickets") public SubscriptionGateway.Ticket ticket(Principal actor, HttpServletRequest request,
            HttpServletResponse response, @RequestBody Request body) {
        response.setHeader("Cache-Control", "no-store");
        return gateway.issue(actor.getName(), request.getSession(), body.devices());
    }
    @GetMapping("/presence/{id}") public Presence presence(Principal actor, @PathVariable String id,
            @RequestParam(required = false) String tenantId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var device = catalog.device(actor.getName(), tenantId, id);
        var access = mqtt.getIfAvailable();
        if (access == null || !access.isRunning()) return new Presence("UNKNOWN");
        var key = new DeviceKey(new TenantId(device.tenantId() == null ? "platform" : device.tenantId()), new DeviceId(device.id()));
        return new Presence(presence.online(key) ? "ONLINE" : "OFFLINE");
    }
}

package com.nebula.iot.device.application;

import com.fasterxml.jackson.databind.*;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.*;
import com.nebula.iot.device.api.*;
import com.nebula.iot.device.api.CatalogViews.*;
import com.nebula.iot.device.domain.*;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

public final class DeviceShadowService {
    private final org.springframework.context.ApplicationEventPublisher events;
    private final CatalogService catalog;
    private final CatalogRepository data;
    private final ThingModelCache models;
    private final AccountDirectory accounts;
    private final TenantAccess tenants;
    private final ObjectMapper json;
    private final Supplier<DeviceShadowStore> stores;

    public DeviceShadowService(CatalogService catalog, CatalogRepository data, ThingModelCache models, AccountDirectory accounts,
            TenantAccess tenants, ObjectMapper json, Supplier<DeviceShadowStore> stores, org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.catalog = catalog; this.data = data; this.accounts = accounts; this.tenants = tenants;
        this.models = models;
        this.json = json; this.stores = stores;
    }
    public DeviceShadow read(String actor, String tenant, String id) {
        var detail = catalog.deviceDetail(actor, tenant, id);
        return readDocument(key(detail.device()), detail.device().modelVersion()).view();
    }
    public DeviceShadow updateDesired(String actor, String tenant, String id, Long version, Map<String, JsonNode> patch) {
        // Resolve and authorize the real resource before touching Redis, including platform-wide reads.
        var detail = catalog.deviceDetail(actor, tenant, id);
        accounts.requirePermission(actor, "device:write");
        if (!accounts.requireEnabled(actor).platformAdmin()) {
            var member = tenants.requireMember(actor, detail.device().tenantId());
            if (!Set.of("OWNER", "ADMIN").contains(member.role())) throw new AccessDeniedException("Tenant member is read-only");
        }
        if (Set.of("DISABLED", "RETIRED").contains(detail.device().lifecycle()) || !detail.product().enabled())
            throw problem(HttpStatus.CONFLICT, "Disabled or retired device/product cannot receive desired state");
        if (version == null || version < 0 || patch == null || patch.isEmpty() || patch.size() > 100)
            throw problem(HttpStatus.BAD_REQUEST, "Shadow version and 1 to 100 desired properties are required");
        validateDesired(detail.model().model(), patch);
        var key = key(detail.device());
        var current = readDocument(key, detail.device().modelVersion());
        if (current.version() != version) throw conflict();
        var next = current.withDesired(patch, Instant.now());
        if (!commit(key, version, next)) throw conflict();
        changed(key, next);
        return next.view();
    }
    /** Only trusted, authenticated ingress calls this port; there is no management reported-state endpoint. */
    public void accept(DeviceMessage message) {
        if (!(message.body() instanceof DeviceMessageBody.PropertyReport report)) return;
        var key = message.device();
        var row = data.device(key.tenantId().value(), key.deviceId().value());
        // Explicit mqtt-dev fixtures are not management registrations and have no managed shadow.
        if (row == null) return;
        if (!row.modelVersion().equals(message.modelVersion()) || !row.lifecycle().equals("ACTIVE"))
            throw problem(HttpStatus.CONFLICT, "Device registration or model is not active");
        var product = data.product(row.scopeKey(), row.productId());
        if (product == null || !product.enabled()) throw problem(HttpStatus.CONFLICT, "Device product is disabled");
        var model = models.get(row.productId(), row.modelVersion());
        if (model == null) throw problem(HttpStatus.CONFLICT, "Device model is unavailable");
        var spec = model.model();
        var values = new HashMap<String, JsonNode>();
        try {
            if (report.properties().isEmpty() || report.properties().size() > 100) throw new IllegalArgumentException();
            report.properties().forEach((id, value) -> {
                var property = spec.properties().get(id);
                if (property == null || !property.access().reportable()) throw new IllegalArgumentException();
                property.schema().validate(value);
                values.put(id, toJson(value));
            });
        } catch (IllegalArgumentException e) {
            throw problem(HttpStatus.BAD_REQUEST, "Report does not match the bound model");
        }
        if (message.occurredAt().isAfter(message.receivedAt().plus(Duration.ofMinutes(5))))
            throw problem(HttpStatus.BAD_REQUEST, "Report clock exceeds allowed future skew");
        for (int attempt = 0; attempt < 8; attempt++) {
            var current = readDocument(key, row.modelVersion());
            var next = current.withReport(values, message.messageId().value(), message.occurredAt(), message.receivedAt());
            if (next == current) return;
            if (commit(key, current.version(), next)) { changed(key, next); return; }
        }
        throw conflict();
    }
    private void changed(DeviceKey key, ShadowDocument doc) {
        events.publishEvent(new DeviceStateChanged(key, doc.modelVersion(), doc.version()));
    }
    private void validateDesired(ModelSpec spec, Map<String, JsonNode> patch) {
        try {
            if (json.writeValueAsBytes(patch).length > 65536) throw new IllegalArgumentException();
            patch.forEach((id, value) -> {
                var property = spec.properties().get(id);
                if (property == null || property.access() != PropertyAccess.READ_WRITE) throw new IllegalArgumentException();
                if (value != null && !value.isNull()) property.schema().validate(fromJson(value));
            });
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw problem(HttpStatus.BAD_REQUEST, "Desired state must contain writable model properties with valid model values (max 64 KiB)");
        }
    }
    private DeviceShadowStore store() {
        var store = stores.get();
        if (store == null) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Device shadow requires Redis to be enabled");
        return store;
    }
    private ShadowDocument readDocument(DeviceKey key, String modelVersion) {
        try {
            var doc = store().read(key, modelVersion);
            if (!doc.modelVersion().equals(modelVersion)) throw problem(HttpStatus.CONFLICT, "Shadow model version differs from registration");
            return doc;
        } catch (DataAccessException e) { throw unavailable(); }
    }
    private boolean commit(DeviceKey key, long version, ShadowDocument document) {
        try { return store().compareAndSet(key, version, document); }
        catch (DataAccessException e) { throw unavailable(); }
        catch (IllegalArgumentException e) { throw problem(HttpStatus.BAD_REQUEST, "Device shadow exceeds its size limit"); }
    }
    private static DeviceKey key(Device device) {
        return new DeviceKey(new TenantId(device.tenantId() == null ? "platform" : device.tenantId()), new DeviceId(device.id()));
    }
    private JsonNode toJson(Value value) { return com.nebula.iot.device.infrastructure.ValueJson.toJson(value); }
    private static Value fromJson(JsonNode value) { return com.nebula.iot.device.infrastructure.ValueJson.fromJson(value); }    private static ResponseStatusException conflict() { return problem(HttpStatus.CONFLICT, "Shadow changed; refresh before retrying"); }
    private static ResponseStatusException unavailable() { return problem(HttpStatus.SERVICE_UNAVAILABLE, "Device shadow storage is unavailable"); }
    private static ResponseStatusException problem(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}


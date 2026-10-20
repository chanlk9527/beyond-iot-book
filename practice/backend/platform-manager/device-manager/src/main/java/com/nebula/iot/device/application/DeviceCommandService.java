package com.nebula.iot.device.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.nebula.iot.core.command.*;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.*;
import com.nebula.iot.device.api.*;
import com.nebula.iot.device.api.CatalogViews.DeviceDetail;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, process-local correlation for online commands. No implicit retries. */
public final class DeviceCommandService {
    private final CatalogService catalog;
    private final AccountDirectory accounts;
    private final TenantAccess tenants;
    private final Supplier<DeviceDownlinkSender> sender;
    private final Clock clock;
    private final DurableCommands durable;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private static final Duration RETENTION = Duration.ofMinutes(10);
    private static final int MAX_ENTRIES = 1000;

    private static final class Entry {
        final DeviceDownlink request;
        DownlinkStatus status;
        Entry(DeviceDownlink request, String commandId, String kind) {
            this.request = request;
            status = new DownlinkStatus(request.messageId().value(), commandId, kind, "SENDING", null,
                    "正在发送，尚未确认设备执行", request.createdAt(), request.expiresAt());
        }
        void update(String state, String code, String message) {
            status = new DownlinkStatus(status.messageId(), status.commandId(), status.kind(), state, code,
                    message, status.createdAt(), status.expiresAt());
        }
    }

    public DeviceCommandService(CatalogService catalog, AccountDirectory accounts, TenantAccess tenants,
            Supplier<DeviceDownlinkSender> sender, Clock clock) {
        this(catalog,accounts,tenants,sender,clock,null);
    }
    public DeviceCommandService(CatalogService catalog,AccountDirectory accounts,TenantAccess tenants,
            Supplier<DeviceDownlinkSender> sender,Clock clock,DurableCommands durable) {
        this.durable=durable;
        this.catalog = catalog; this.accounts = accounts; this.tenants = tenants;
        this.sender = sender; this.clock = clock;
    }

    public DownlinkStatus write(String actor, String tenant, String device, Map<String, JsonNode> properties) {
        var detail = writable(actor, tenant, device);
        var values = values(properties);
        if (values.isEmpty()) throw problem(HttpStatus.BAD_REQUEST, "请选择需要下发的属性");
        try {
            values.forEach((id, value) -> {
                var property = detail.model().model().properties().get(id);
                if (property == null || property.access() == PropertyAccess.READ_ONLY)
                    throw new IllegalArgumentException("不可写入属性：" + id);
                property.schema().validate(value);
            });
        } catch (IllegalArgumentException e) { throw problem(HttpStatus.BAD_REQUEST, e.getMessage()); }
        return dispatch(actor,detail, new DownlinkBody.PropertyWrite(values), null, "PROPERTY_WRITE");
    }

    public DownlinkStatus invoke(String actor, String tenant, String device, String operationId, Map<String, JsonNode> inputs) {
        var detail = writable(actor, tenant, device);
        var values = values(inputs);
        if (operationId == null || operationId.isBlank()) throw problem(HttpStatus.BAD_REQUEST, "请选择操作");
        var operation = detail.model().model().operations().get(operationId);
        if (operation == null) throw problem(HttpStatus.BAD_REQUEST, "当前绑定模型没有此操作");
        try { operation.validateInputs(values); }
        catch (IllegalArgumentException e) { throw problem(HttpStatus.BAD_REQUEST, e.getMessage()); }
        var commandId = UUID.randomUUID().toString();
        return dispatch(actor,detail, new DownlinkBody.OperationInvoke(new CommandId(commandId), operationId, values), commandId, "OPERATION");
    }

    private DeviceDetail writable(String actor, String tenant, String device) {
        var detail = catalog.deviceDetail(actor, tenant, device);
        accounts.requirePermission(actor, "device:write");
        if (!accounts.requireEnabled(actor).platformAdmin()) {
            var membership = tenants.requireMember(actor, detail.device().tenantId());
            if (!Set.of("OWNER", "ADMIN").contains(membership.role())) throw new AccessDeniedException("Tenant member is read-only");
        }
        if (!"ACTIVE".equals(detail.device().lifecycle()) || !detail.product().enabled())
            throw problem(HttpStatus.CONFLICT, "设备及产品必须启用才能下发");
        return detail;
    }

    private DownlinkStatus dispatch(String actor,DeviceDetail detail, DownlinkBody body, String commandId, String kind) {
        var now = clock.instant();
        var key = new DeviceKey(new TenantId(detail.device().tenantId() == null ? "platform" : detail.device().tenantId()), new DeviceId(detail.device().id()));
        var request = new DeviceDownlink(new MessageId(UUID.randomUUID().toString()), key, detail.device().modelVersion(),
                now, now.plusSeconds(30), Optional.empty(), Optional.empty(), body);
        if(durable!=null)return durable.enqueue(request,commandId,kind,actor);
        var entry = new Entry(request, commandId, kind);
        synchronized (entries) {
            entries.values().removeIf(value -> !now.isBefore(value.request.createdAt().plus(RETENTION)));
            if (entries.size() >= MAX_ENTRIES) throw problem(HttpStatus.TOO_MANY_REQUESTS, "下发记录已满，请稍后再试");
            entries.put(request.messageId().value(), entry);
        }
        try {
            var transport = sender.get();
            if (transport == null) throw new IllegalStateException("MQTT 下发服务未启用");
            transport.send(request).whenComplete((receipt, error) -> {
                synchronized (entry) {
                    if (!entry.status.status().equals("SENDING")) return; // A fast application reply wins over PUBACK.
                    if (error == null) entry.update("DELIVERED", null, "MQTT 投递已确认，等待设备执行回执");
                    else entry.update("DISPATCH_FAILED", "DELIVERY_UNCONFIRMED", "下发未确认，请检查设备连接；结果未知，请勿盲目重发");
                }
            });
        } catch (RuntimeException error) {
            synchronized (entry) { entry.update("DISPATCH_FAILED", "GATEWAY_UNAVAILABLE", "下发服务不可用，请检查 MQTT 接入与设备连接"); }
        }
        return snapshot(entry);
    }

    public DownlinkStatus get(String actor, String tenant, String device, String messageId) {
        var detail = catalog.deviceDetail(actor, tenant, device);
        if(durable!=null) {
            String scope=detail.device().tenantId()==null?"platform":detail.device().tenantId();
            var status=durable.status(new DeviceKey(new TenantId(scope),new DeviceId(device)),messageId);
            if(status==null)throw problem(HttpStatus.NOT_FOUND,"下发记录不存在");
            return status;
        }
        var entry = entries.get(messageId);
        String tenantId = detail.device().tenantId() == null ? "platform" : detail.device().tenantId();
        if (entry == null || !entry.request.device().deviceId().value().equals(device)
                || !entry.request.device().tenantId().value().equals(tenantId)
                || !clock.instant().isBefore(entry.request.createdAt().plus(RETENTION)))
            throw problem(HttpStatus.NOT_FOUND, "下发记录不存在或已过期");
        return snapshot(entry);
    }

    private DownlinkStatus snapshot(Entry entry) {
        synchronized (entry) {
            if (Set.of("SENDING", "DELIVERED", "ACCEPTED").contains(entry.status.status())
                    && !clock.instant().isBefore(entry.request.expiresAt()))
                entry.update("TIMED_OUT", "REPLY_TIMEOUT", "等待设备回执超时，执行结果未知，请勿盲目重发");
            return entry.status;
        }
    }

    /** Only authenticated gateway ingress may call this method. Match device, model, type and both IDs. */
    public void accept(DeviceMessage message) {
        if(durable!=null){durable.accept(message);return;}
        if (message.origin() != MessageOrigin.DEVICE || message.replyTo().isEmpty() || message.result().isEmpty()) return;
        var entry = entries.get(message.replyTo().get().value());
        if (entry == null || !entry.request.device().equals(message.device()) || !entry.request.modelVersion().equals(message.modelVersion())) return;
        synchronized (entry) {
            if (!Set.of("SENDING", "DELIVERED", "ACCEPTED", "TIMED_OUT", "DISPATCH_FAILED").contains(entry.status.status())) return;
            if (!clock.instant().isBefore(entry.request.createdAt().plus(RETENTION))) return;
            var result = message.result().get();
            if (entry.request.body() instanceof DownlinkBody.OperationInvoke invoke
                    && message.body() instanceof DeviceMessageBody.CommandReply reply
                    && invoke.commandId().equals(reply.commandId())) {
                entry.update(reply.status().name(), result.code(), result.message().orElse(reply.status().name()));
            } else if (entry.request.body() instanceof DownlinkBody.PropertyWrite
                    && (message.body() instanceof DeviceMessageBody.PropertyWriteReply
                    || message.body() instanceof DeviceMessageBody.RequestFailure failure && failure.kind() == DeviceMessageBody.RequestKind.PROPERTY_WRITE)) {
                entry.update(result.isSuccess() ? "SUCCEEDED" : "FAILED", result.code(), result.message().orElse(result.isSuccess() ? "设备已确认属性写入" : "设备拒绝属性写入"));
            }
        }
    }

    private static Map<String, Value> values(Map<String, JsonNode> input) {
        if (input == null || input.size() > 100) throw problem(HttpStatus.BAD_REQUEST, "参数必须是最多 100 个字段的对象");
        var values = new HashMap<String, Value>();
        int size = 0;
        try {
            for (var item : input.entrySet()) {
                var value = item.getValue();
                if (item.getKey() == null || value == null || value.isNull()) throw new IllegalArgumentException("参数不能为空");
                size += item.getKey().length() + value.toString().length();
                if (size > 16384) throw new IllegalArgumentException("参数过大");
                Value scalar = com.nebula.iot.device.infrastructure.ValueJson.fromJson(value);
                values.put(item.getKey(), scalar);
            }
        } catch (IllegalArgumentException e) { throw problem(HttpStatus.BAD_REQUEST, e.getMessage()); }
        return Map.copyOf(values);
    }

    private static ResponseStatusException problem(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}

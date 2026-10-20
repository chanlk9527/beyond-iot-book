package com.nebula.iot.device.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.device.api.*;
import com.nebula.iot.device.domain.CatalogRepository;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.storage.*;
import com.nebula.iot.storage.HistoryStore.*;
import com.nebula.iot.tenant.api.TenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Owns product policy and device history semantics. Vendor details stay behind HistoryStore. */
public final class DeviceHistoryService {
    public record PolicyView(ProductHistoryPolicy policy, long revision, Capabilities capabilities) {}
    public record HistoryItem(Instant occurredAt, Instant receivedAt, String messageId, String modelVersion, String valueType, String payload) {}
    public record HistoryPage(List<HistoryItem> items, String nextCursor) {}
    private record Work(String fingerprint, String records, boolean completed) {}
    private final CatalogService catalog;
    private final CatalogRepository data;
    private final AccountDirectory accounts;
    private final TenantAccess tenants;
    private final ThingModelCache models;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Supplier<HistoryStore> stores;
    private final Clock clock;
    private String cleanupCursor = "";
    public DeviceHistoryService(CatalogService catalog, CatalogRepository data, AccountDirectory accounts, TenantAccess tenants,
            ThingModelCache models, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, Supplier<HistoryStore> stores, Clock clock) {
        this.catalog=catalog; this.data=data; this.accounts=accounts; this.tenants=tenants; this.models=models;
        this.jdbc=jdbc; this.tx=tx; this.json=json; this.stores=stores; this.clock=clock;
    }
    public Capabilities capabilities() {
        var store=stores.get(); return store == null ? new Capabilities("disabled",false,false,false,false,false,false,0) : store.capabilities();
    }
    private HistoryStore store() {
        var store=stores.get(); if(store==null) throw problem(HttpStatus.SERVICE_UNAVAILABLE,"History storage is not enabled"); return store;
    }
    public PolicyView policy(String actor,String tenant,String product) {
        catalog.product(actor,tenant,product); return policyFor(product);
    }
    private PolicyView policyFor(String product) {
        var rows=jdbc.query("SELECT policy_json,revision FROM device_history_policy WHERE product_id=?",
                (rs,n)->new PolicyView(decode(rs.getString(1),ProductHistoryPolicy.class),rs.getLong(2),capabilities()),product);
        return rows.isEmpty()?new PolicyView(ProductHistoryPolicy.initial(),0,capabilities()):rows.getFirst();
    }
    public PolicyView savePolicy(String actor,String tenant,String product,long revision,ProductHistoryPolicy policy) {
        if(policy==null) throw problem(HttpStatus.BAD_REQUEST,"History policy is required");
        var actual=catalog.product(actor,tenant,product);
        accounts.requirePermission(actor,"product:write");
        if(!accounts.requireEnabled(actor).platformAdmin()) {
            var member=tenants.requireMember(actor,actual.tenantId());
            if(!Set.of("OWNER","ADMIN").contains(member.role())) throw new AccessDeniedException("Tenant member is read-only");
        }
        try { policy.validate(capabilities()); }
        catch(IllegalArgumentException e) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY,e.getMessage()); }
        String encoded=encode(policy);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>65536 || revision<0) throw problem(HttpStatus.BAD_REQUEST,"Invalid history policy");
        return tx.execute(status->{
            String scope=actual.tenantId()==null?"platform":actual.tenantId();
            var locked=data.lockProduct(scope,product);
            if(locked==null) throw problem(HttpStatus.NOT_FOUND,"Product unavailable");
            var current=policyFor(product);
            if(current.revision()!=revision) throw problem(HttpStatus.CONFLICT,"History policy changed; refresh before saving");
            // Overrides may target the draft or any published version still bound by devices.
            var draft=decode(locked.draftModel(),CatalogViews.ModelSpec.class);
            var propertyIds=new HashSet<>(draft.properties().keySet()); var eventIds=new HashSet<>(draft.events().keySet());
            for(var model:data.models(product)) {
                var spec=decode(model.modelJson(),CatalogViews.ModelSpec.class); propertyIds.addAll(spec.properties().keySet()); eventIds.addAll(spec.events().keySet());
            }
            if(!propertyIds.containsAll(policy.overrides().keySet()) || !eventIds.containsAll(policy.eventOverrides().keySet()))
                throw problem(HttpStatus.BAD_REQUEST,"Policy references an unknown model identifier");
            long next=revision+1;
            if(revision==0) jdbc.update("INSERT INTO device_history_policy(product_id,policy_json,revision,updated_at) VALUES (?,?,?,?)",product,encoded,next,clock.millis());
            else jdbc.update("UPDATE device_history_policy SET policy_json=?,revision=?,updated_at=? WHERE product_id=?",encoded,next,clock.millis(),product);
            data.audit(UUID.randomUUID().toString(),scope,accounts.requireEnabled(actor).id(),"HISTORY_POLICY_SAVED",product);
            return new PolicyView(policy,next,capabilities());
        });
    }
    /** Latest-value filtering is independent of history storage being enabled. Missing values are never synthesized. */
    public DeviceMessage filterLatest(DeviceMessage message) {
        if(!(message.body() instanceof DeviceMessageBody.PropertyReport report)) return message;
        var row=data.device(message.device().tenantId().value(),message.device().deviceId().value());
        if(row==null) return message;
        var policy=policyFor(row.productId()).policy();
        var values=new HashMap<String,Value>();
        report.properties().forEach((id,value)->{if(policy.rule(Kind.PROPERTY,id).latest()) values.put(id,value);});
        if(values.isEmpty()) return null;
        return new DeviceMessage(message.messageId(),message.device(),message.modelVersion(),message.occurredAt(),message.receivedAt(),
                message.origin(),new DeviceMessageBody.PropertyReport(values));
    }
    public void accept(DeviceMessage message) {
        if(message.origin()!=MessageOrigin.DEVICE || !(message.body() instanceof DeviceMessageBody.PropertyReport || message.body() instanceof DeviceMessageBody.EventReport)) return;
        if(!capabilities().enabled()) return;
        if(message.occurredAt().isAfter(message.receivedAt().plusSeconds(300))) throw new IllegalArgumentException("History report clock is in the future");
        if(message.occurredAt().isBefore(clock.instant().minus(Duration.ofDays(capabilities().maximumRetentionDays())))) return;
        String inboxId=hash(message.device().tenantId().value()+"\n"+message.device().deviceId().value()+"\n"+message.messageId().value());
        var work=tx.execute(status->{
            var row=data.device(message.device().tenantId().value(),message.device().deviceId().value());
            if(row==null) return null;
            var product=data.lockProduct(row.scopeKey(),row.productId());
            if(!row.lifecycle().equals("ACTIVE") || !row.modelVersion().equals(message.modelVersion()) || product==null || !product.enabled())
                throw new IllegalArgumentException("Device history source is not active");
            var model=models.get(row.productId(),row.modelVersion());
            if(model==null) throw new IllegalArgumentException("Device model unavailable");
            var policy=policyFor(row.productId()).policy();
            var records=new ArrayList<Observation>();
            var fingerprintValues=new TreeMap<String,Object>();
            fingerprintValues.put("model",message.modelVersion()); fingerprintValues.put("at",message.occurredAt().toEpochMilli());
            if(message.body() instanceof DeviceMessageBody.PropertyReport report) {
                if(report.properties().isEmpty()||report.properties().size()>100) throw new IllegalArgumentException("Invalid report size");
                var values=new TreeMap<String,JsonNode>();
                report.properties().forEach((id,value)->{
                    var definition=model.model().properties().get(id);
                    if(definition==null || !definition.access().reportable()) throw new IllegalArgumentException("Invalid reported property");
                    definition.schema().validate(value); values.put(id,value(value));
                    collect(message,row.productId(),Kind.PROPERTY,id,value.type().name(),value(value),policy.rule(Kind.PROPERTY,id),records);
                });
                fingerprintValues.put("properties",values);
            } else if(message.body() instanceof DeviceMessageBody.EventReport event) {
                var definition=model.model().events().get(event.eventId());
                if(definition==null) throw new IllegalArgumentException("Unknown event");
                definition.validateFields(event.fields());
                var fields=new TreeMap<String,JsonNode>(); event.fields().forEach((id,value)->fields.put(id,value(value)));
                fingerprintValues.put("event",event.eventId()); fingerprintValues.put("fields",fields);
                collect(message,row.productId(),Kind.EVENT,event.eventId(),"EVENT",json.valueToTree(fields),policy.rule(Kind.EVENT,event.eventId()),records);
            }
            String fingerprint=hash(encode(fingerprintValues));
            var existing=jdbc.query("SELECT fingerprint,records_json,completed FROM device_history_inbox WHERE id=?",(rs,n)->new Work(rs.getString(1),rs.getString(2),rs.getBoolean(3)),inboxId);
            if(!existing.isEmpty()) {
                if(!existing.getFirst().fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Message ID reused with different history data");
                return existing.getFirst();
            }
            String serialized=encode(records);
            if(serialized.getBytes(StandardCharsets.UTF_8).length>262144) throw new IllegalArgumentException("History work exceeds bounds");
            jdbc.update("INSERT INTO device_history_inbox(id,fingerprint,records_json,completed,received_at) VALUES (?,?,?,FALSE,?)",inboxId,fingerprint,serialized,clock.millis());
            return new Work(fingerprint,serialized,false);
        });
        if(work==null || work.completed()) return;
        List<Observation> records;
        try { records=json.readValue(work.records(),new TypeReference<List<Observation>>(){}); }
        catch(java.io.IOException e) { throw new IllegalStateException("Stored history work is invalid",e); }
        // A crash after TDengine accepts the batch is safe: stable timestamps and message IDs make retry idempotent.
        store().append(records);
        jdbc.update("UPDATE device_history_inbox SET completed=TRUE WHERE id=?",inboxId);
    }
    private void collect(DeviceMessage message,String product,Kind kind,String id,String type,JsonNode payload,ProductHistoryPolicy.Rule rule,List<Observation> records) {
        rule.validate(capabilities(),kind==Kind.EVENT);
        if(rule.history().equals("none") || message.occurredAt().isBefore(clock.instant().minus(Duration.ofDays(rule.days())))) return;
        var series=new Series(message.device().tenantId().value(),message.device().deviceId().value(),product,kind,id);
        var o=new Observation(series,message.occurredAt(),message.messageId().value(),message.receivedAt(),message.modelVersion(),type,encode(payload));
        records.add(o);
        String seriesId=hash(encode(series));
        var exists=jdbc.queryForObject("SELECT COUNT(*) FROM device_history_series WHERE id=?",Long.class,seriesId);
        if(exists==0) jdbc.update("INSERT INTO device_history_series(id,product_id,kind,identifier,series_json,retention_days) VALUES (?,?,?,?,?,?)",seriesId,product,kind.name(),id,encode(series),rule.days());
        else jdbc.update("UPDATE device_history_series SET retention_days=? WHERE id=?",rule.days(),seriesId);
    }
    public HistoryPage query(String actor,String tenant,String device,Kind kind,String identifier,Instant from,Instant to,String cursor,int limit) {
        var detail=catalog.deviceDetail(actor,tenant,device);
        if(!identifier.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || from==null || to==null || !from.isBefore(to)
                || Duration.between(from,to).compareTo(Duration.ofDays(366))>0 || limit<1 || limit>100)
            throw problem(HttpStatus.BAD_REQUEST,"History queries require a valid identifier, a range up to 366 days, and limit 1-100");
        var series=new Series(detail.device().tenantId()==null?"platform":detail.device().tenantId(),device,detail.product().id(),kind,identifier);
        // Query before model inspection: old model versions and removed identifiers remain historical facts.
        store();
        var retention=jdbc.query("SELECT retention_days FROM device_history_series WHERE id=?",(rs,n)->rs.getInt(1),hash(encode(series)));
        var rule=policyFor(detail.product().id()).policy().rule(kind,identifier);
        int days=rule.history().equals("time")?rule.days():retention.isEmpty()?capabilities().maximumRetentionDays():retention.getFirst();
        Instant floor=clock.instant().minus(Duration.ofDays(days));
        if(from.isBefore(floor)) from=floor;
        if(!from.isBefore(to)) return new HistoryPage(List.of(),null);
        Cursor before=null;
        if(cursor!=null&&!cursor.isBlank()) {
            if(cursor.length()>1024) throw problem(HttpStatus.BAD_REQUEST,"Invalid history cursor");
            try { before=decode(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8),Cursor.class); }
            catch(RuntimeException e) { throw problem(HttpStatus.BAD_REQUEST,"Invalid history cursor"); }
        }
        var page=store().query(new Query(series,from,to,before,limit));
        var items=page.items().stream().map(o->new HistoryItem(o.occurredAt(),o.receivedAt(),o.messageId(),o.modelVersion(),o.valueType(),o.payload())).toList();
        return new HistoryPage(items,page.next()==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(encode(page.next()).getBytes(StandardCharsets.UTF_8)));
    }
    /** Bounded sweep; every worker can run it since exact-series deletes are idempotent. */
    public synchronized void cleanup() {
        if(!capabilities().enabled()) return;
        // A durable pending batch also recovers independently of Kafka retry limits or local-process restarts.
        var pending=jdbc.query("SELECT id,records_json FROM device_history_inbox WHERE completed=FALSE ORDER BY received_at LIMIT 100",
                (rs,n)->Map.entry(rs.getString(1),rs.getString(2)));
        for(var batch:pending) {
            try {
                store().append(json.readValue(batch.getValue(),new TypeReference<List<Observation>>(){}));
                jdbc.update("UPDATE device_history_inbox SET completed=TRUE WHERE id=?",batch.getKey());
            } catch(java.io.IOException e) { throw new IllegalStateException("Invalid pending history work",e); }
        }
        var rows=jdbc.query("SELECT id,product_id,kind,identifier,series_json,retention_days FROM device_history_series WHERE id>? ORDER BY id LIMIT 100",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6)},cleanupCursor);
        for(var row:rows) {
            var rule=policyFor((String)row[1]).policy().rule(Kind.valueOf((String)row[2]),(String)row[3]);
            int days=rule.history().equals("time")?rule.days():(Integer)row[5];
            store().deleteBefore(decode((String)row[4],Series.class),clock.instant().minus(Duration.ofDays(days)));
            cleanupCursor=(String)row[0];
        }
        if(rows.size()<100) cleanupCursor="";
        // Preserve decisions across the documented seven-day replay window. Do not remove pending work.
        var expired=jdbc.query("SELECT id FROM device_history_inbox WHERE completed=TRUE AND received_at<? ORDER BY received_at LIMIT 1000",(rs,n)->rs.getString(1),clock.millis()-Duration.ofDays(7).toMillis());
        for(var id:expired) jdbc.update("DELETE FROM device_history_inbox WHERE id=? AND completed=TRUE",id);
    }
    private JsonNode value(Value value) { return com.nebula.iot.device.infrastructure.ValueJson.toJson(value); }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalArgumentException("Invalid history data",e);} }
    private <T>T decode(String value,Class<T> type) { try{return json.readValue(value,type);}catch(java.io.IOException e){throw new IllegalStateException("Invalid stored history data",e);} }
    private static String hash(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    private static ResponseStatusException problem(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}

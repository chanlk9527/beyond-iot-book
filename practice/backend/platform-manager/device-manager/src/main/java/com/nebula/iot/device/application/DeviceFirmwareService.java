package com.nebula.iot.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.core.message.*;
import com.nebula.iot.device.api.DeviceFirmware.*;
import com.nebula.iot.device.domain.*;
import com.nebula.iot.device.domain.FirmwareInventoryDocument.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Transactional(readOnly=true)
public class DeviceFirmwareService {
    private static final Duration REPLAY_WINDOW=Duration.ofDays(7);
    private final CatalogService catalog;
    private final CatalogRepository devices;
    private final FirmwareRepository firmware;
    private final FirmwareInventoryRepository inventories;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration maximumAge;
    public DeviceFirmwareService(CatalogService catalog,CatalogRepository devices,FirmwareRepository firmware,FirmwareInventoryRepository inventories,ObjectMapper json,Clock clock,Duration maximumAge) {
        if (maximumAge==null || maximumAge.isNegative() || maximumAge.isZero() || maximumAge.compareTo(REPLAY_WINDOW)>0)
            throw new IllegalArgumentException("Firmware report maximum age must be positive and at most seven days");
        this.catalog=catalog;this.devices=devices;this.firmware=firmware;this.inventories=inventories;this.json=json;this.clock=clock;this.maximumAge=maximumAge;
    }
    /** Trusted ingress only. Management APIs cannot set reported firmware or hardware. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void accept(DeviceMessage message) {
        boolean info=message.body() instanceof DeviceMessageBody.DeviceInfoReport;
        if (!info && !(message.body() instanceof DeviceMessageBody.OtaVersionReport)) return;
        Instant now=clock.instant();
        if (message.receivedAt().isBefore(now.minus(REPLAY_WINDOW))) return;
        if (message.receivedAt().isAfter(now.plusSeconds(300))) throw new IllegalArgumentException("Invalid ingress receipt time");
        String scope=message.device().tenantId().value(),id=message.device().deviceId().value();
        var row=devices.device(scope,id); if (row==null) return; // Unmanaged mqtt-dev fixtures.
        var product=devices.lockProduct(scope,row.productId());
        row=devices.device(scope,id);
        if (row==null) return;
        if (!row.modelVersion().equals(message.modelVersion()) || !row.lifecycle().equals("ACTIVE") || product==null || !product.enabled()
                || devices.connection(id)==null || message.productId()!=null && !row.productId().equals(message.productId().value()))
            throw new IllegalArgumentException("Firmware report source is not eligible");
        text(message.messageId().value(),128,"message ID");
        String kind=info?"INFO":"VERSIONS";
        var body=new TreeMap<String,Object>();body.put("model",message.modelVersion());body.put("origin",message.origin().name());body.put("occurredAt",message.occurredAt().toString());
        var stamp=new Stamp(message.messageId().value(),message.occurredAt(),message.receivedAt());
        var current=inventories.read(scope,id);Info nextInfo=current.information();Versions nextVersions=current.versions();
        boolean changed=false;
        if (message.body() instanceof DeviceMessageBody.DeviceInfoReport report) {
            text(report.firmwareVersion(),100,"firmware version");text(report.hardwareVersion(),100,"hardware version");text(report.bootId(),128,"boot ID");
            report.sdkVersion().ifPresent(value->text(value,100,"SDK version"));
            body.put("firmwareVersion",report.firmwareVersion());body.put("hardwareVersion",report.hardwareVersion());body.put("bootId",report.bootId());
            body.put("sdkVersion",report.sdkVersion().orElse(null));body.put("clockSynced",report.clockSynced());
            if (nextInfo==null || stamp.newerThan(nextInfo.stamp())) {
                var bootAt=nextInfo!=null && nextInfo.bootId().equals(report.bootId())?nextInfo.bootObservedAt():stamp.receivedAt();
                nextInfo=new Info(report.firmwareVersion(),report.hardwareVersion(),report.bootId(),report.sdkVersion().orElse(null),report.clockSynced(),stamp,bootAt);changed=true;
            }
        } else if (message.body() instanceof DeviceMessageBody.OtaVersionReport report) {
            report.versions().forEach((component,version)-> {
                if (!component.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid firmware component");
                text(version,100,"component version");
            });
            body.put("versions",new TreeMap<>(report.versions()));
            if (nextVersions==null || stamp.newerThan(nextVersions.stamp())) { nextVersions=new Versions(report.versions(),stamp);changed=true; }
        }
        String fingerprint=fingerprint(body),seen=inventories.fingerprint(id,message.messageId().value(),kind);
        if (seen!=null) { if (!seen.equals(fingerprint)) throw new IllegalArgumentException("Firmware report ID reused with different content");return; }
        inventories.remember(id,message.messageId().value(),kind,fingerprint,message.receivedAt().toEpochMilli());
        if (changed) inventories.save(scope,id,new FirmwareInventoryDocument(nextInfo,nextVersions,current.revision()+1));
    }
    public Inventory inventory(String actor,String tenant,String id) {
        var device=catalog.device(actor,tenant,id);String scope=device.tenantId()==null?"platform":device.tenantId();
        return view(inventories.read(scope,id),clock.instant());
    }
    /** Authorize every requested device before the single inventory query; no cross-scope partial results. */
    public Map<String,Inventory> inventories(String actor,String tenant,List<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>100 || new HashSet<>(ids).size()!=ids.size()
                || ids.stream().anyMatch(id->id==null || id.isBlank() || id.length()>36))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择1～100台不同设备");
        ids.forEach(id->catalog.device(actor,tenant,id));
        var documents=inventories.readMany(ids);Instant now=clock.instant();var result=new LinkedHashMap<String,Inventory>();
        ids.forEach(id->result.put(id,view(documents.getOrDefault(id,FirmwareInventoryDocument.empty()),now)));
        return result;
    }
    public Preflight preflight(String actor,String tenant,String id,int number,String versionId) {
        var device=catalog.device(actor,tenant,id);String scope=device.tenantId()==null?"platform":device.tenantId();
        var product=catalog.product(actor,device.tenantId(),device.productId());
        var channel=firmware.channels(product.id()).stream().filter(c->c.channel()==number).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"固件通道不存在"));
        var target=firmware.version(product.id(),number,versionId);
        if (target==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"该产品通道下的固件版本不存在");
        Instant now=clock.instant();var inventory=view(inventories.read(scope,id),now);
        var reasons=new ArrayList<Check>();var warnings=new ArrayList<Check>();
        if (!"ACTIVE".equals(device.lifecycle())) reasons.add(new Check("DEVICE_INACTIVE","设备未启用或已退役"));
        if (!product.enabled()) reasons.add(new Check("PRODUCT_DISABLED","产品已停用"));
        if (devices.connection(id)==null) reasons.add(new Check("ACCESS_INELIGIBLE","设备、产品或所属租户不具备接入资格"));
        if (!target.enabled()) reasons.add(new Check("TARGET_DISABLED","目标固件版本尚未启用"));
        var info=inventory.information();String hardware=info==null?null:info.hardwareVersion();
        if (info==null) reasons.add(new Check("INFO_MISSING","设备尚未上报硬件与启动信息"));
        else {
            if (info.stale()) reasons.add(new Check("INFO_STALE","硬件与启动信息已过期，请设备重新上报"));
            if (!target.hardwareVersions().contains(hardware)) reasons.add(new Check("HARDWARE_INCOMPATIBLE","实际硬件版本不在目标固件兼容清单中"));
        }
        var component=inventory.components().stream().filter(c->c.component().equals(channel.component())).findFirst().orElse(null);
        String current=component==null?null:component.version();
        if (component==null) reasons.add(new Check("COMPONENT_MISSING","设备尚未上报该组件的实际版本"));
        else {
            if (component.stale()) reasons.add(new Check("COMPONENT_STALE","组件版本上报已过期"));
            if (component.beforeCurrentBoot()) reasons.add(new Check("COMPONENT_BEFORE_BOOT","组件版本早于本次启动信息，请重新上报"));
            if (target.version().equals(current)) reasons.add(new Check("ALREADY_INSTALLED","设备已经运行目标版本，不支持同版本重装"));
            else {
                try { FirmwareVersionRule.valueOf(channel.versionRule()).validate(current); }
                catch (IllegalArgumentException e) { reasons.add(new Check("VERSION_INVALID","设备上报版本不符合当前通道的版本规则")); }
                if (!target.sourceVersions().contains(current)) reasons.add(new Check("SOURCE_NOT_ALLOWED","当前版本不在允许升级的源版本清单中"));
            }
        }
        if (target.artifact().fileId()==null) warnings.add(new Check("EXTERNAL_FILE_UNVERIFIED","外部登记固件尚未验证实际文件内容与签名"));
        else if (firmware.file(product.id(),number,target.artifact().fileId())==null) reasons.add(new Check("FILE_MISSING","托管固件文件记录不存在"));
        warnings.add(new Check("DISPATCH_CHECKS_REQUIRED","仅检查静态兼容性；在线状态、下载可达性、电量、工作状态和设备安装条件仍需确认"));
        return new Preflight(id,number,channel.component(),current,target.version(),hardware,inventory.revision(),target.revision(),reasons.isEmpty(),List.copyOf(reasons),List.copyOf(warnings),now);
    }
    private Inventory view(FirmwareInventoryDocument doc,Instant now) {
        var info=doc.information();var versions=doc.versions();var components=new TreeMap<String,Component>();
        if (versions!=null) versions.values().forEach((name,version)->components.put(name,new Component(name,version,"ota.version.report",versions.stamp().occurredAt(),versions.stamp().receivedAt(),stale(versions.stamp().receivedAt(),now),info!=null&&versions.stamp().receivedAt().isBefore(info.bootObservedAt()))));
        if (info!=null && (versions==null || !versions.values().containsKey("main") || !versions.stamp().newerThan(info.stamp())))
            components.put("main",new Component("main",info.firmwareVersion(),"thing.info.report",info.stamp().occurredAt(),info.stamp().receivedAt(),stale(info.stamp().receivedAt(),now),false));
        var information=info==null?null:new Information(info.hardwareVersion(),info.bootId(),info.sdkVersion(),info.clockSynced(),info.stamp().occurredAt(),info.stamp().receivedAt(),info.bootObservedAt(),stale(info.stamp().receivedAt(),now));
        return new Inventory(doc.revision(),information,List.copyOf(components.values()),now,maximumAge.toSeconds());
    }
    private boolean stale(Instant received,Instant now) { return received.isBefore(now.minus(maximumAge)); }
    private String fingerprint(Object body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8))); }
        catch (java.io.IOException | NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void text(String value,int max,String field) {
        if (value==null || value.isBlank() || value.length()>max || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid "+field);
    }
    @Transactional public void cleanup() { inventories.cleanup(clock.instant().minus(REPLAY_WINDOW).toEpochMilli()); }
}

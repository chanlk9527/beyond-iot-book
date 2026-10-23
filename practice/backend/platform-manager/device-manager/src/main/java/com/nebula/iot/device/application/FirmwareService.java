package com.nebula.iot.device.application;

import com.nebula.iot.core.command.DownlinkBody.FirmwarePackage;
import com.nebula.iot.device.api.CatalogViews.Page;
import com.nebula.iot.device.api.CatalogViews.Product;
import com.nebula.iot.device.api.FirmwareViews.*;
import com.nebula.iot.device.domain.*;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import java.net.URI;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Transactional(readOnly = true)
public class FirmwareService {
    private final FirmwareRepository data;
    private final CatalogRepository products;
    private final CatalogService catalog;
    private final AccountDirectory accounts;
    private final TenantAccess tenants;
    public FirmwareService(FirmwareRepository data, CatalogRepository products, CatalogService catalog, AccountDirectory accounts, TenantAccess tenants) {
        this.data=data; this.products=products; this.catalog=catalog; this.accounts=accounts; this.tenants=tenants;
    }
    public record CreateChannel(Integer channel, String component, String name, String versionRule) { }
    public record CreateVersion(String version, String description, List<String> sourceVersions, List<String> hardwareVersions, Artifact artifact) { }
    public List<Channel> channels(String actor, String tenant, String productId) {
        catalog.product(actor, tenant, productId); return data.channels(productId);
    }
    public Page<Version> versions(String actor, String tenant, String productId, int channel, int page, int size) {
        catalog.product(actor, tenant, productId); channel(productId, channel);
        if (page<1 || size<1 || size>100) bad("分页参数无效");
        return new Page<>(data.versions(productId,channel,size,((long)page-1)*size),data.count(productId,channel),page,size);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Channel createChannel(String actor, String tenant, String productId, CreateChannel input) {
        var p=write(actor,tenant,productId,"product:write",true);
        if (input==null || input.channel()==null) bad("请选择升级通道");
        int number=input.channel();
        if (!(Set.of(0,1,3,9).contains(number) || number>9 && number<=255)) bad("通道须为0、1、3、9或10～255的扩展通道");
        text(input.component(),64,true,"组件标识"); text(input.name(),100,true,"通道名称");
        if (!input.component().matches("[a-z][a-z0-9_]{0,63}")) bad("组件标识须为小写字母开头的字母、数字或下划线");
        var standard=Map.of(0,"main",1,"bluetooth",3,"zigbee",9,"mcu");
        if (standard.containsKey(number) && !standard.get(number).equals(input.component())
                || !standard.containsKey(number) && standard.containsValue(input.component())) bad("标准通道与组件标识不匹配");
        String rule=input.versionRule()==null?"TUYA":input.versionRule(); rule(rule);
        if (data.channels(productId).size()>=16) conflict("每个产品最多16个固件通道");
        var c=new Channel(number,input.component(),input.name().strip(),rule,System.currentTimeMillis());
        try { data.insertChannel(productId,scope(p),c); }
        catch (DuplicateKeyException e) { conflict("通道编号或组件标识已存在"); }
        audit(actor,p,"FIRMWARE_CHANNEL_CREATED",productId); return c;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Version createVersion(String actor, String tenant, String productId, int channel, CreateVersion input) {
        var p=write(actor,tenant,productId,"product:write",true);
        var c=channel(productId,channel); var rule=rule(c.versionRule());
        if (input==null) bad("请填写固件版本");
        validate(rule,input.version()); text(input.description(),1000,false,"更新说明");
        var sources=values(input.sourceVersions(),"源版本"); var hardware=values(input.hardwareVersions(),"硬件版本");
        for (String source:sources) {
            validate(rule,source);
            var order=rule.compare(source,input.version());
            if (source.equals(input.version()) || order.isPresent() && order.getAsInt()>=0) bad("源版本必须低于目标版本，自定义版本也不能与目标相同");
        }
        var artifact=input.artifact();
        if (artifact!=null && artifact.fileId()!=null) {
            var file=data.file(productId,channel,artifact.fileId());
            if (file==null) missing();
            artifact=new Artifact(null,file.size(),file.sha256(),file.keyId(),file.signature(),file.id());
        }
        if (artifact==null || artifact.size()==null) bad("请填写完整的固件包信息");
        if (artifact.fileId()==null) {
            text(artifact.url(),2048,true,"下载地址"); text(artifact.keyId(),100,true,"签名密钥标识");
            try { new FirmwarePackage(URI.create(artifact.url()),artifact.size(),artifact.sha256(),artifact.keyId(),artifact.signature()); }
            catch (IllegalArgumentException e) { bad("固件包信息无效："+e.getMessage()); }
        }
        var v=new Version(UUID.randomUUID().toString(),channel,input.version(),input.description(),sources,hardware,artifact,false,0,System.currentTimeMillis());
        try { data.insertVersion(productId,v); }
        catch (DuplicateKeyException e) { conflict("该通道下的固件版本已存在，不能覆盖"); }
        audit(actor,p,"FIRMWARE_VERSION_CREATED",v.id()); return v;
    }
    /** Short authorization transaction before receiving/uploading file content. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Product authorizeFileUpload(String actor,String tenant,String productId,int number) {
        var p=write(actor,tenant,productId,"product:write",true); channel(productId,number); return p;
    }
    /** Recheck eligibility after S3 upload, without holding a database lock during network I/O. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void registerFile(String actor,String tenant,String productId,int number,FirmwareFile file) {
        var p=write(actor,tenant,productId,"product:write",true); channel(productId,number);
        data.insertFile(productId,number,file); audit(actor,p,"FIRMWARE_FILE_UPLOADED",file.id());
    }
    public FirmwareFile downloadableFile(String actor,String tenant,String productId,int number,String versionId) {
        catalog.product(actor,tenant,productId);
        var v=data.version(productId,number,versionId); if (v==null) missing();
        if (v.artifact().fileId()==null) conflict("外部登记版本没有平台托管文件");
        var file=data.file(productId,number,v.artifact().fileId()); if (file==null) missing();
        return file;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Version status(String actor, String tenant, String productId, int channel, String id, Boolean enabled, Long revision) {
        if (enabled==null || revision==null || revision<0) bad("请提供启停状态和修订号");
        // Disabling remains possible after the product is disabled.
        var p=write(actor,tenant,productId,"product:publish",enabled);
        var v=data.version(productId,channel,id); if (v==null) missing();
        if (v.revision()!=revision) conflict("固件版本已变更，请刷新后重试");
        if (v.enabled()==enabled) return v;
        if (data.status(productId,id,enabled,revision)!=1) conflict("固件版本已变更，请刷新后重试");
        audit(actor,p,enabled?"FIRMWARE_VERSION_ENABLED":"FIRMWARE_VERSION_DISABLED",id);
        return data.version(productId,channel,id);
    }
    private Product write(String actor, String tenant, String id, String permission, boolean requireEnabled) {
        accounts.requirePermission(actor,permission);
        var p=catalog.product(actor,tenant,id);
        if (!accounts.requireEnabled(actor).platformAdmin()) {
            var member=tenants.requireMember(actor,p.tenantId());
            if (!Set.of("OWNER","ADMIN").contains(member.role())) throw new AccessDeniedException("Tenant member is read-only");
        }
        var locked=products.lockProduct(scope(p),id);
        if (locked==null) missing();
        if (requireEnabled && !locked.enabled()) conflict("请先启用产品");
        return p;
    }
    private Channel channel(String productId,int number) {
        return data.channels(productId).stream().filter(c->c.channel()==number).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"固件通道不存在"));
    }
    private static FirmwareVersionRule rule(String name) {
        try { return FirmwareVersionRule.valueOf(name); }
        catch (IllegalArgumentException | NullPointerException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"版本规则须为TUYA或OPAQUE"); }
    }
    private static void validate(FirmwareVersionRule rule,String version) {
        try { rule.validate(version); } catch (IllegalArgumentException e) { bad(e.getMessage()); }
    }
    private static List<String> values(List<String> input,String field) {
        if (input==null || input.isEmpty() || input.size()>100) bad(field+"须填写1～100项，不能以空列表表示全部");
        for (String value:input) { text(value,100,true,field); if (!value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) bad(field+"不能包含首尾空白或控制字符"); }
        if (new HashSet<>(input).size()!=input.size()) bad(field+"不能重复");
        return List.copyOf(input);
    }
    private static String scope(Product p) { return p.tenantId()==null?"platform":p.tenantId(); }
    private void audit(String actor,Product p,String action,String id) { products.audit(UUID.randomUUID().toString(),scope(p),accounts.requireEnabled(actor).id(),action,id); }
    private static void text(String s,int max,boolean required,String field) { if (s==null || s.length()>max || required && s.isBlank()) bad(field+"须在"+max+"字符内" ); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private static void missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"固件版本或产品不存在"); }
}

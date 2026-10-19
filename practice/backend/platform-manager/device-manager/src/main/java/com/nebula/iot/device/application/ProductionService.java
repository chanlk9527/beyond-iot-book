package com.nebula.iot.device.application;

import com.nebula.iot.device.api.CatalogViews.Page;
import com.nebula.iot.device.api.ProductionViews.*;
import com.nebula.iot.device.domain.CatalogRepository;
import com.nebula.iot.device.domain.ProductionRepository;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

/** Production evidence is separate from device eligibility and App ownership. */
@Transactional(readOnly = true)
public class ProductionService {
    private final ProductionRepository data;
    private final CatalogRepository devices;
    private final CatalogService catalog;
    private final AccountDirectory accounts;
    private final TenantAccess tenants;
    public ProductionService(ProductionRepository data,CatalogRepository devices,CatalogService catalog,AccountDirectory accounts,TenantAccess tenants) {
        this.data=data; this.devices=devices; this.catalog=catalog; this.accounts=accounts; this.tenants=tenants;
    }
    public record Create(String code,String name,String productId,String modelVersion,Integer quantity,String hardwareVersion,String firmwareVersion) { }
    public Page<Batch> list(String actor,String tenant,String query,int page,int size) {
        access(actor,tenant,"production:read",false);
        if(query==null || query.length()>100 || page<1 || size<1 || size>100) bad("Invalid pagination");
        return new Page<>(data.batches(tenant,query,size,((long)page-1)*size),data.count(tenant,query),page,size);
    }
    public Detail detail(String actor,String tenant,String id,int eventPage) {
        var b=read(actor,tenant,id,"production:read",false);
        if(eventPage<1) bad("Invalid event page");
        return detail(b,eventPage);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public Detail create(String actor,String tenant,Create input) {
        access(actor,tenant,"production:write",true);
        if(input.code()==null || !input.code().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) bad("Batch code must contain 1 to 40 letters, digits, dots, underscores or hyphens");
        text(input.name(),100,true,"Batch name"); text(input.hardwareVersion(),100,true,"Hardware version"); text(input.firmwareVersion(),100,true,"Firmware version");
        if(input.quantity()==null || input.quantity()<1 || input.quantity()>50) bad("Batch quantity must be 1 to 50");
        var p=catalog.product(actor,tenant,input.productId());
        access(actor,p.tenantId(),"production:write",true);
        var locked=devices.lockProduct(p.tenantId()==null?"platform":p.tenantId(),p.id());
        if(locked==null || !locked.enabled()) conflict("Product must be enabled");
        if(input.modelVersion()==null || devices.model(p.id(),input.modelVersion())==null) bad("Select a published model version");
        var b=new Batch(UUID.randomUUID().toString(),p.tenantId(),p.tenantId()==null?"platform":p.tenantId(),input.code(),input.name().strip(),
                p.id(),p.name(),input.modelVersion(),input.quantity(),input.hardwareVersion().strip(),input.firmwareVersion().strip(),"PLANNED",0,"","",System.currentTimeMillis(),null);
        try { data.insert(b); } catch(DuplicateKeyException duplicate) { conflict("Batch code already exists in this scope"); }
        event(actor,b,null,"BATCH_CREATED","","","",null);
        return detail(b,1);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public Detail generate(String actor,String tenant,String id,Long revision) {
        var b=mutate(actor,tenant,id,revision);
        if(!b.status().equals("PLANNED")) conflict("Devices have already been generated");
        for(int i=1;i<=b.quantity();i++) {
            String code=b.code()+"-"+String.format(java.util.Locale.ROOT,"%04d",i);
            var credential=catalog.register(actor,b.tenantId(),b.productId(),code,code,b.modelVersion());
            // Includes tenant eligibility, without reading another domain's tables.
            if(devices.connection(credential.device().id())==null) conflict("Device scope is not eligible for production");
            data.addItem(b.id(),credential.device().id(),i);
        }
        data.advance(id,"PRODUCING"); event(actor,b,null,"DEVICES_GENERATED","","","",null);
        return detail(data.batch(id,false),1);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public List<ProvisioningCredential> credentials(String actor,String tenant,String id,Long revision) {
        var b=read(actor,tenant,id,"production:credential",true);
        b=locked(b,revision);
        if(!Set.of("PRODUCING","RELEASED").contains(b.status())) conflict("Only an undelivered generated batch can export credentials");
        // Serialize with credential rotation while building a consistent export.
        lockProduct(b);
        var result=new ArrayList<ProvisioningCredential>();
        for(var item:data.items(id)) {
            var c=catalog.credential(actor,b.tenantId(),item.deviceId());
            result.add(new ProvisioningCredential(item.deviceId(),item.code(),c.clientId(),c.username(),c.secret(),c.device().credentialVersion()));
        }
        event(actor,b,null,"CREDENTIALS_EXPORTED","","","",null);
        return List.copyOf(result);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public Detail burn(String actor,String tenant,String id,String deviceId,Long revision,Long credentialVersion,String station,String note) {
        var b=mutate(actor,tenant,id,revision); editable(b);
        text(station,100,true,"Station"); text(note,1000,false,"Note");
        var item=item(b,deviceId); eligible(item);
        version(item,credentialVersion);
        data.burned(id,deviceId,credentialVersion);
        data.advance(id,"PRODUCING");
        event(actor,b,deviceId,"BURN_CONFIRMED",station,"CONFIRMED",note,credentialVersion);
        return detail(data.batch(id,false),1);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public Detail test(String actor,String tenant,String id,String deviceId,Long revision,Long credentialVersion,String result,String station,String note) {
        var b=mutate(actor,tenant,id,revision); editable(b);
        text(station,100,true,"Station"); text(note,1000,true,"Test evidence");
        if(!Set.of("PASS","FAIL").contains(result==null?"":result)) bad("Test result must be PASS or FAIL");
        var item=item(b,deviceId); eligible(item); version(item,credentialVersion);
        if(!Objects.equals(item.burnedVersion(),credentialVersion)) conflict("Confirm burning the current credential version before testing");
        data.tested(id,deviceId,credentialVersion,result); data.advance(id,"PRODUCING");
        event(actor,b,deviceId,"FACTORY_TEST",station,result,note,credentialVersion);
        return detail(data.batch(id,false),1);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public Detail release(String actor,String tenant,String id,Long revision,String note) {
        var b=mutate(actor,tenant,id,revision);
        if(!b.status().equals("PRODUCING")) conflict("Only a batch in production can be released");
        text(note,1000,true,"Release evidence"); ready(b);
        data.advance(id,"RELEASED"); event(actor,b,null,"BATCH_RELEASED","","RELEASED",note,null);
        return detail(data.batch(id,false),1);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public Detail deliver(String actor,String tenant,String id,Long revision,String recipient,String reference,String note) {
        var b=mutate(actor,tenant,id,revision);
        if(!b.status().equals("RELEASED")) conflict("Release all devices before delivery");
        text(recipient,200,true,"Recipient"); text(reference,100,true,"Delivery reference"); text(note,1000,false,"Note"); ready(b);
        data.deliver(id,recipient.strip(),reference.strip(),System.currentTimeMillis());
        event(actor,b,null,"BATCH_DELIVERED","","DELIVERED",note,null);
        return detail(data.batch(id,false),1);
    }
    private void ready(Batch b) {
        var items=data.items(b.id());
        if(items.size()!=b.quantity()) conflict("Batch devices are incomplete");
        for(var i:items) {
            eligible(i);
            if(!Objects.equals(i.burnedVersion(),i.credentialVersion()) || !Objects.equals(i.testedVersion(),i.credentialVersion()) || !"PASS".equals(i.testResult()))
                conflict("Every device must pass testing with its current burned credential version");
        }
    }
    private void eligible(Item i) {
        if(!i.lifecycle().equals("ACTIVE") || devices.connection(i.deviceId())==null) conflict("Device, product and tenant must be enabled");
    }
    private void version(Item i,Long version) { if(version==null || version<1) bad("Credential version required"); if(i.credentialVersion()!=version) conflict("Credential changed; refresh before recording evidence"); }
    private Item item(Batch b,String id) { return data.items(b.id()).stream().filter(i->i.deviceId().equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Device not in this batch")); }
    private void editable(Batch b) { if(!Set.of("PRODUCING","RELEASED").contains(b.status())) conflict("Generate devices first; delivered evidence cannot be modified"); }
    private Detail detail(Batch b,int page) { return new Detail(b,data.items(b.id()),data.events(b.id(),page),data.eventCount(b.id())); }
    private Batch mutate(String actor,String tenant,String id,Long revision) {
        var b=locked(read(actor,tenant,id,"production:write",true),revision);
        lockProduct(b); return b;
    }
    private Batch locked(Batch b,Long revision) {
        if(revision==null || revision<0) bad("Revision required");
        var current=data.batch(b.id(),true);
        if(current.revision()!=revision) conflict("Batch changed; refresh and retry");
        return current;
    }
    private void lockProduct(Batch b) {
        var p=devices.lockProduct(b.scopeKey(),b.productId());
        if(p==null || !p.enabled()) conflict("Product must be enabled");
    }
    private Batch read(String actor,String tenant,String id,String permission,boolean write) {
        access(actor,tenant,permission,write);
        var b=data.batch(id,false);
        if(b==null || tenant!=null && !tenant.equals(b.tenantId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Batch unavailable in this scope");
        access(actor,b.tenantId(),permission,write); return b;
    }
    private void access(String actor,String tenant,String permission,boolean write) {
        accounts.requirePermission(actor,permission);
        if(tenant==null) { accounts.requirePlatformAdmin(actor); return; }
        if(accounts.requireEnabled(actor).platformAdmin()) return;
        var member=tenants.requireMember(actor,tenant);
        if(write && !Set.of("OWNER","ADMIN").contains(member.role())) throw new AccessDeniedException("Tenant member is read-only");
    }
    private void event(String actor,Batch b,String device,String action,String station,String result,String note,Long version) {
        data.event(b.id(),new Event(UUID.randomUUID().toString(),device,accounts.requireEnabled(actor).username(),action,station.strip(),result,note.strip(),version,System.currentTimeMillis()));
    }
    private static void text(String s,int max,boolean required,String field) { if(s==null || s.length()>max || required && s.isBlank()) bad(field+" is required and must not exceed "+max+" characters"); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
}

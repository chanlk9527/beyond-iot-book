package com.nebula.iot.device.application;

import com.nebula.iot.device.api.FirmwareViews.*;
import com.nebula.iot.device.domain.FirmwareFile;
import com.nebula.iot.file.*;
import java.io.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Business validation around the reusable object storage; no transaction spans S3 I/O. */
public class FirmwareFileService {
    private final FirmwareService firmware;
    private final ObjectProvider<ObjectStorage> storage;
    private final long maximumBytes;
    private final Duration downloadLifetime;
    private final Map<String,PublicKey> keys;
    public FirmwareFileService(FirmwareService firmware,ObjectProvider<ObjectStorage> storage,long maximumBytes,Duration downloadLifetime,Map<String,String> trustedKeys) {
        this.firmware=firmware; this.storage=storage; this.maximumBytes=maximumBytes; this.downloadLifetime=downloadLifetime;
        var parsed=new HashMap<String,PublicKey>();
        trustedKeys.forEach((id,value)-> {
            try {
                if (id==null || id.isBlank() || id.length()>100) throw new IllegalArgumentException("Invalid signing key ID");
                parsed.put(id,KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value))));
            } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalArgumentException("Invalid configured firmware public key: "+id,e); }
        });
        keys=Map.copyOf(parsed);
    }
    public FileCapabilities capabilities(String actor,String tenant,String productId) {
        firmware.channels(actor,tenant,productId);
        return new FileCapabilities(storage.getIfAvailable()!=null,maximumBytes,keys.keySet().stream().sorted().toList());
    }
    public UploadedFile upload(String actor,String tenant,String productId,int channel,String keyId,String signature,InputStream content) throws IOException {
        var product=firmware.authorizeFileUpload(actor,tenant,productId,channel);
        var store=requireStorage(); PublicKey key=keys.get(keyId);
        if (key==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"固件签名密钥不在可信公钥列表中");
        byte[] signed;
        try { if (signature==null || signature.length()>100) throw new IllegalArgumentException(); signed=Base64.getDecoder().decode(signature); if (signed.length!=64) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException | NullPointerException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ed25519签名格式无效"); }
        try (var staged=StagedUpload.read(content,maximumBytes)) {
            verify(staged,key,signed);
            String id=UUID.randomUUID().toString();
            String objectKey="firmware/"+(product.tenantId()==null?"platform":product.tenantId())+"/"+productId+"/"+channel+"/"+id+".bin";
            var object=store.put(objectKey,staged,"application/octet-stream");
            var file=new FirmwareFile(id,object.reference(),object.size(),object.sha256(),keyId,signature,System.currentTimeMillis());
            try { firmware.registerFile(actor,tenant,productId,channel,file); }
            catch (RuntimeException failure) {
                try { store.delete(object.reference()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            return new UploadedFile(id,file.size(),file.sha256(),keyId,true);
        } catch (StagedUpload.TooLargeException e) { throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"固件超过允许上传大小"); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"固件文件不能为空"); }
    }
    public ObjectStorage.Download download(String actor,String tenant,String productId,int channel,String versionId) {
        var file=firmware.downloadableFile(actor,tenant,productId,channel,versionId);
        var result=requireStorage().download(file.reference(),downloadLifetime);
        if (!"https".equalsIgnoreCase(result.url().getScheme())) throw new ResponseStatusException(HttpStatus.CONFLICT,"固件下载需要可达的HTTPS存储端点");
        return result;
    }
    private ObjectStorage requireStorage() {
        var result=storage.getIfAvailable();
        if (result==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"文件存储尚未启用");
        return result;
    }
    private static void verify(StagedUpload upload,PublicKey key,byte[] signed) throws IOException {
        try {
            var verifier=Signature.getInstance("Ed25519"); verifier.initVerify(key);
            try (var in=upload.open()) { byte[] buffer=new byte[64*1024]; int n; while ((n=in.read(buffer))!=-1) verifier.update(buffer,0,n); }
            if (!verifier.verify(signed)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"固件文件签名校验失败");
        } catch (GeneralSecurityException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"固件签名无效"); }
    }
}

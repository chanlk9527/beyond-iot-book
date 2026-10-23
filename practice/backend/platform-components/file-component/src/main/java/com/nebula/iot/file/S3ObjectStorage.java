package com.nebula.iot.file;

import java.io.InputStream;
import java.time.*;
import java.util.Map;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public final class S3ObjectStorage implements ObjectStorage {
    private final S3Client client;
    private final S3Presigner signer;
    private final String bucket;
    private final Runnable credentialCleanup;
    public S3ObjectStorage(S3Client client,S3Presigner signer,String bucket) {
        this(client,signer,bucket,()->{});
    }
    S3ObjectStorage(S3Client client,S3Presigner signer,String bucket,Runnable credentialCleanup) {
        new Reference(bucket,"validation",null); this.client=client; this.signer=signer; this.bucket=bucket; this.credentialCleanup=credentialCleanup;
    }
    public StoredObject put(String key,StagedUpload upload,String contentType) {
        var reference=new Reference(bucket,key,null);
        if (contentType==null || !contentType.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) throw new IllegalArgumentException("Invalid content type");
        try {
            var response=client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                    .contentLength(upload.size()).metadata(Map.of("sha256",upload.sha256())).build(),RequestBody.fromFile(upload.path()));
            return new StoredObject(new Reference(bucket,key,response.versionId()),upload.size(),upload.sha256(),contentType);
        } catch (RuntimeException e) { throw new FileStorageException("upload",e); }
    }
    public InputStream open(Reference reference) {
        check(reference);
        try { return client.getObject(GetObjectRequest.builder().bucket(bucket).key(reference.key()).versionId(reference.versionId()).build()); }
        catch (RuntimeException e) { throw new FileStorageException("read",e); }
    }
    public void delete(Reference reference) {
        check(reference);
        try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(reference.key()).versionId(reference.versionId()).build()); }
        catch (RuntimeException e) { throw new FileStorageException("delete",e); }
    }
    public Download download(Reference reference,Duration lifetime) {
        check(reference);
        if (lifetime==null || lifetime.compareTo(Duration.ofSeconds(1))<0 || lifetime.compareTo(Duration.ofHours(1))>0)
            throw new IllegalArgumentException("Download lifetime must be between one second and one hour");
        try {
            var request=GetObjectRequest.builder().bucket(bucket).key(reference.key()).versionId(reference.versionId()).build();
            var signed=signer.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(lifetime).getObjectRequest(request).build());
            return new Download(signed.url().toURI(),signed.expiration());
        } catch (Exception e) { throw new FileStorageException("download signing",e); }
    }
    private void check(Reference reference) {
        if (reference==null || !bucket.equals(reference.bucket())) throw new IllegalArgumentException("Object belongs to another configured bucket");
    }
    public void close() { try { signer.close(); } finally { try { client.close(); } finally { credentialCleanup.run(); } } }
}

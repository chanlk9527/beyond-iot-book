package com.nebula.iot.file;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nebula.files")
public class FileStorageProperties {
    private boolean enabled;
    private String bucket, region="us-east-1", accessKey, secretKey;
    private URI endpoint;
    private boolean pathStyleAccess;
    private long maximumUploadBytes=64L*1024*1024;
    private Duration downloadLifetime=Duration.ofMinutes(10);
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled=v; }
    public String getBucket() { return bucket; } public void setBucket(String v) { bucket=v; }
    public String getRegion() { return region; } public void setRegion(String v) { region=v; }
    public String getAccessKey() { return accessKey; } public void setAccessKey(String v) { accessKey=v; }
    public String getSecretKey() { return secretKey; } public void setSecretKey(String v) { secretKey=v; }
    public URI getEndpoint() { return endpoint; } public void setEndpoint(URI v) { endpoint=v==null||v.toString().isBlank()?null:v; }
    public boolean isPathStyleAccess() { return pathStyleAccess; } public void setPathStyleAccess(boolean v) { pathStyleAccess=v; }
    public long getMaximumUploadBytes() { return maximumUploadBytes; } public void setMaximumUploadBytes(long v) { maximumUploadBytes=v; }
    public Duration getDownloadLifetime() { return downloadLifetime; } public void setDownloadLifetime(Duration v) { downloadLifetime=v; }
    @Override public String toString() { return "FileStorageProperties[credentials redacted]"; }
    public void validate() {
        new ObjectStorage.Reference(bucket,"validation",null);
        if (region==null || region.isBlank() || maximumUploadBytes<1 || maximumUploadBytes>5L*1024*1024*1024)
            throw new IllegalArgumentException("File region and upload limit (1 byte to 5 GiB) are required");
        if (downloadLifetime==null || downloadLifetime.compareTo(Duration.ofSeconds(1))<0 || downloadLifetime.compareTo(Duration.ofHours(1))>0)
            throw new IllegalArgumentException("File download lifetime must be between one second and one hour");
        if ((accessKey!=null&&!accessKey.isBlank()) != (secretKey!=null&&!secretKey.isBlank()))
            throw new IllegalArgumentException("Configure both file access key and secret key or neither");
        if (endpoint!=null && (!java.util.Set.of("http","https").contains(endpoint.getScheme()) || endpoint.getHost()==null
                || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || endpoint.getPath()!=null && !endpoint.getPath().isEmpty() && !endpoint.getPath().equals("/")))
            throw new IllegalArgumentException("File endpoint must be an HTTP(S) origin without credentials or path");
    }
}

package com.nebula.iot.file;

import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.checksums.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@AutoConfiguration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageAutoConfiguration {
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="nebula.files.enabled",havingValue="true")
    @ConditionalOnMissingBean(ObjectStorage.class)
    public ObjectStorage objectStorage(FileStorageProperties p) {
        p.validate();
        AwsCredentialsProvider credentials=p.getAccessKey()==null || p.getAccessKey().isBlank()
                ?DefaultCredentialsProvider.builder().build():StaticCredentialsProvider.create(AwsBasicCredentials.create(p.getAccessKey(),p.getSecretKey()));
        Runnable cleanup=credentials instanceof DefaultCredentialsProvider provider?provider::close:()->{};
        var client=S3Client.builder().region(Region.of(p.getRegion())).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(60)))
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofMinutes(5)).apiCallAttemptTimeout(Duration.ofMinutes(2)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.isPathStyleAccess()).chunkedEncodingEnabled(false).build());
        var signer=S3Presigner.builder().region(Region.of(p.getRegion())).credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.isPathStyleAccess()).checksumValidationEnabled(false).build());
        if (p.getEndpoint()!=null) { client.endpointOverride(p.getEndpoint()); signer.endpointOverride(p.getEndpoint()); }
        var builtClient=client.build();
        try { return new S3ObjectStorage(builtClient,signer.build(),p.getBucket(),cleanup); }
        catch (RuntimeException e) { try { builtClient.close(); } finally { cleanup.run(); } throw e; }
    }
}

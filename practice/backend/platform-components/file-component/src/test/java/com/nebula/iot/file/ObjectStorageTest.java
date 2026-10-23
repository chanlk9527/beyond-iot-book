package com.nebula.iot.file;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ObjectStorageTest {
    @Test void stagingCalculatesRealBytesAndRemovesTemporaryFile() throws Exception {
        byte[] bytes="firmware\u0000binary".getBytes(StandardCharsets.UTF_8);
        var staged=StagedUpload.read(new ByteArrayInputStream(bytes),bytes.length);
        var path=staged.path();assertTrue(Files.exists(path));assertEquals(bytes.length,staged.size());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),staged.sha256());
        try (var in=staged.open()) { assertArrayEquals(bytes,in.readAllBytes()); }
        staged.close();assertFalse(Files.exists(path));assertThrows(IllegalStateException.class,staged::open);
        assertThrows(StagedUpload.TooLargeException.class,()->StagedUpload.read(new ByteArrayInputStream(bytes),bytes.length-1));
        assertThrows(IllegalArgumentException.class,()->StagedUpload.read(new ByteArrayInputStream(new byte[0]),1));
        assertThrows(IOException.class,()->StagedUpload.read(new InputStream(){public int read() throws IOException { throw new IOException("broken stream"); }},100));
    }
    @Test void realS3ClientUsesConfiguredPathStyleEndpointAndSignsFreshDownloads() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var body=new AtomicReference<byte[]>();var requestPath=new AtomicReference<String>();var authorization=new AtomicReference<String>();
        var checksum=new AtomicReference<String>();var query=new AtomicReference<String>();
        server.createContext("/",exchange->{
            requestPath.set(exchange.getRequestURI().getPath());query.set(exchange.getRequestURI().getRawQuery());authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            switch(exchange.getRequestMethod()) {
                case "PUT" -> { body.set(exchange.getRequestBody().readAllBytes());checksum.set(exchange.getRequestHeaders().getFirst("x-amz-meta-sha256"));exchange.getResponseHeaders().set("x-amz-version-id","revision-one");exchange.getResponseHeaders().set("ETag","\"test\"");exchange.sendResponseHeaders(200,-1); }
                case "GET" -> { exchange.sendResponseHeaders(200,body.get().length);exchange.getResponseBody().write(body.get()); }
                case "DELETE" -> exchange.sendResponseHeaders(204,-1);
                default -> exchange.sendResponseHeaders(400,-1);
            }
            exchange.close();
        });server.start();
        var properties=new FileStorageProperties();properties.setBucket("test-bucket");properties.setAccessKey("test-access");properties.setSecretKey("test-secret");
        properties.setEndpoint(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));properties.setPathStyleAccess(true);
        try (var storage=new FileStorageAutoConfiguration().objectStorage(properties);var staged=StagedUpload.read(new ByteArrayInputStream(new byte[]{0,1,2,3,4}),1024)) {
            var result=storage.put("cloud/tenant-a/file.bin",staged,"application/octet-stream");
            assertEquals("/test-bucket/cloud/tenant-a/file.bin",requestPath.get());assertArrayEquals(new byte[]{0,1,2,3,4},body.get());
            assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=test-access/"));assertEquals(staged.sha256(),checksum.get());
            assertEquals("revision-one",result.reference().versionId());
            try (var in=storage.open(result.reference())) { assertArrayEquals(body.get(),in.readAllBytes()); }
            assertTrue(query.get().contains("versionId=revision-one"));
            var download=storage.download(result.reference(),Duration.ofMinutes(2));
            assertEquals("127.0.0.1",download.url().getHost());assertEquals("/test-bucket/cloud/tenant-a/file.bin",download.url().getPath());
            assertTrue(download.url().getRawQuery().contains("X-Amz-Expires=120"));assertTrue(download.url().getRawQuery().contains("X-Amz-SignedHeaders=host"));
            assertFalse(download.toString().contains("X-Amz-Signature"));
            assertThrows(IllegalArgumentException.class,()->storage.download(result.reference(),Duration.ofDays(2)));
            assertThrows(IllegalArgumentException.class,()->storage.open(new ObjectStorage.Reference("another-bucket","file.bin",null)));
            storage.delete(result.reference());assertTrue(query.get().contains("versionId=revision-one"));
        } finally { server.stop(0); }
    }
    @Test void standardAwsEndpointNeedsNoProviderSpecificImplementation() {
        var p=new FileStorageProperties();p.setBucket("firmware-test");p.setRegion("eu-west-1");p.setAccessKey("test");p.setSecretKey("test-secret");
        try (var storage=new FileStorageAutoConfiguration().objectStorage(p)) {
            var url=storage.download(new ObjectStorage.Reference("firmware-test","a.bin",null),Duration.ofMinutes(1)).url();
            assertTrue(url.getHost().contains("s3.eu-west-1.amazonaws.com"));assertEquals("https",url.getScheme());
        }
    }
    @Test void storageIsOptInAndRejectsPartialCredentialsOrUnsafeEndpoints() {
        var runner=new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FileStorageAutoConfiguration.class));
        runner.run(context->assertEquals(0,context.getBeansOfType(ObjectStorage.class).size()));
        runner.withPropertyValues("nebula.files.enabled=true","nebula.files.bucket=test","nebula.files.access-key=test","nebula.files.secret-key=test-secret","nebula.files.endpoint=")
                .run(context->{assertNull(context.getStartupFailure());assertNotNull(context.getBean(ObjectStorage.class));});
        runner.withPropertyValues("nebula.files.enabled=true","nebula.files.bucket=test","nebula.files.access-key=only-one").run(context->assertNotNull(context.getStartupFailure()));
        var p=new FileStorageProperties();p.setBucket("test");p.setEndpoint(URI.create("https://user:secret@example.com/path"));assertThrows(IllegalArgumentException.class,p::validate);
        assertThrows(IllegalArgumentException.class,()->new ObjectStorage.Reference("test","../escape",null));
    }
}

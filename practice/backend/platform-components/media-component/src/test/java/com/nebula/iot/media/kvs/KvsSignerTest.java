package com.nebula.iot.media.kvs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.*;
import static org.junit.jupiter.api.Assertions.*;

class KvsSignerTest {
    @Test void signsKvsViewerRequestIncludingTemporarySecurityToken() throws Exception {
        String secret = "test-secret";
        try (var provider = new KvsMediaProvider("us-east-1", StaticCredentialsProvider.create(
                AwsSessionCredentials.create("test-key",secret,"token/with+special=")), new ObjectMapper())) {
            URI signed = provider.signedUri("wss://example.test", "arn:aws:kinesisvideo:us-east-1:123456789012:channel/camera/1", "viewer-1");
            assertEquals("wss",signed.getScheme());
            Map<String,String> query = Arrays.stream(signed.getRawQuery().split("&")).map(s -> s.split("=",2))
                    .collect(Collectors.toMap(p -> p[0],p -> p[1]));
            assertEquals("299",query.get("X-Amz-Expires"));
            assertEquals("token/with+special=", URLDecoder.decode(query.get("X-Amz-Security-Token"), StandardCharsets.UTF_8));
            assertEquals("viewer-1",query.get("X-Amz-ClientId"));
            String signature = query.remove("X-Amz-Signature");
            String canonicalQuery = query.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey()+"="+e.getValue()).collect(Collectors.joining("&"));
            String scope = URLDecoder.decode(query.get("X-Amz-Credential"),StandardCharsets.UTF_8).substring("test-key/".length());
            String canonical = "GET\n/\n"+canonicalQuery+"\nhost:example.test\n\nhost\n"+sha("");
            String stringToSign = "AWS4-HMAC-SHA256\n"+query.get("X-Amz-Date")+"\n"+scope+"\n"+sha(canonical);
            byte[] key = ("AWS4"+secret).getBytes(StandardCharsets.UTF_8);
            for (String part : scope.split("/")) key = hmac(key, part);
            assertEquals(HexFormat.of().formatHex(hmac(key,stringToSign)),signature);
            assertThrows(IllegalArgumentException.class, () -> provider.signedUri("ws://example.test","channel","client"));
        }
    }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static byte[] hmac(byte[] key, String value) throws Exception {
        var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}

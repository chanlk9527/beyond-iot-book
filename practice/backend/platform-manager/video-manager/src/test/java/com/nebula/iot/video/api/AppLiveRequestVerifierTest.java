package com.nebula.iot.video.api;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AppLiveRequestVerifierTest {
    static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    static final String SECRET = "test-secret";
    static final String APP = "home-app";

    @Test void acceptsFreshBindingClaimAndRejectsTamperingOrExpiry() throws Exception {
        var verifier = new AppLiveRequestVerifier(APP, SECRET, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
        String ts = Long.toString(NOW.toEpochMilli());
        String requestId = "req-1";
        String sig = sign(APP, "user-1", "tenant-1", "device-1", "main", ts, requestId);
        assertEquals("user-1", verifier.verify(APP, "user-1", "tenant-1", "device-1", "main", ts, requestId, sig).subject());
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "user-1", "tenant-1", "device-2", "main", ts, requestId, sig));
        String old = Long.toString(NOW.minusSeconds(61).toEpochMilli());
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "user-1", "tenant-1", "device-1", "main", old, "req-3", sign(APP, "user-1", "tenant-1", "device-1", "main", old, "req-3")));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "user-1", "tenant-1", "device-1", "main", Long.toString(Long.MAX_VALUE), "req-4", "x"));
    }

    @Test void rejectsUnknownAppAndReplayedRequestId() throws Exception {
        var verifier = new AppLiveRequestVerifier(APP, SECRET, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
        String ts = Long.toString(NOW.toEpochMilli());
        String signature = sign(APP, "user-1", "tenant-1", "device-1", "main", ts, "req-1");
        assertThrows(IllegalArgumentException.class, () -> verifier.verify("other-app", "user-1", "tenant-1", "device-1", "main", ts, "req-2", signature));
        verifier.verify(APP, "user-1", "tenant-1", "device-1", "main", ts, "req-1", signature);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "user-1", "tenant-1", "device-1", "main", ts, "req-1", signature));
    }

    @Test void rejectsMissingRequestIdentity() {
        var verifier = new AppLiveRequestVerifier(APP, SECRET, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "user-1", "tenant-1", "device-1", "main", Long.toString(NOW.toEpochMilli()), "", "sig"));
    }

    @Test void capacityMustNotEvictUnexpiredReplayRecords() throws Exception {
        var verifier = new AppLiveRequestVerifier(APP, SECRET, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60), 1);
        String ts = Long.toString(NOW.toEpochMilli());
        String sig = sign(APP, "u", "t", "d", "main", ts, "one");
        verifier.verify(APP, "u", "t", "d", "main", ts, "one", sig);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "u", "t", "d", "main", ts, "two", sign(APP, "u", "t", "d", "main", ts, "two")));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "u", "t", "d", "main", ts, "one", sig));
    }

    @Test void futureTimestampMustNotPurgeStillValidRequests() throws Exception {
        var verifier = new AppLiveRequestVerifier(APP, SECRET, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
        String old = Long.toString(NOW.minusSeconds(30).toEpochMilli());
        String future = Long.toString(NOW.plusSeconds(60).toEpochMilli());
        String sig = sign(APP, "u", "t", "d", "main", old, "one");
        verifier.verify(APP, "u", "t", "d", "main", old, "one", sig);
        verifier.verify(APP, "u", "t", "d", "main", future, "two", sign(APP, "u", "t", "d", "main", future, "two"));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "u", "t", "d", "main", old, "one", sig));
    }

    @Test void rejectsDelimiterInjectionEvenWithValidSignature() throws Exception {
        var verifier = new AppLiveRequestVerifier(APP, SECRET, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
        String ts = Long.toString(NOW.toEpochMilli());
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(APP, "u\nt", "t", "d", "main", ts, "one", sign(APP, "u\nt", "t", "d", "main", ts, "one")));
    }

    private static String sign(String app, String subject, String tenant, String device, String channel, String timestamp, String requestId) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((app+"\n"+subject+"\n"+tenant+"\n"+device+"\n"+channel+"\n"+timestamp+"\n"+requestId).getBytes(StandardCharsets.UTF_8)));
    }
}

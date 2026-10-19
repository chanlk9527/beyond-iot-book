package com.nebula.iot.video.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies the short-lived binding claim produced by the trusted App Server. */
public final class AppLiveRequestVerifier {
    public record Claim(String appId, String subject, String tenantId, String deviceId, String channelId, String requestId) {}
    private static final int DEFAULT_REPLAY_CAPACITY = 4096;
    private final String expectedAppId;
    private final byte[] secret;
    private final Clock clock;
    private final Duration skew;
    private final int replayCapacity;
    private final Map<String, Instant> seenRequests = new LinkedHashMap<>();

    public AppLiveRequestVerifier(String expectedAppId, String secret, Clock clock, Duration skew) {
        this(expectedAppId, secret, clock, skew, DEFAULT_REPLAY_CAPACITY);
    }

    public AppLiveRequestVerifier(String expectedAppId, String secret, Clock clock, Duration skew, int replayCapacity) {
        if (expectedAppId == null || expectedAppId.isBlank() || expectedAppId.length() > 64) throw new IllegalArgumentException("App ID required");
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("App signing secret required");
        if (skew.isNegative() || skew.isZero() || skew.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Invalid App signature skew");
        if (replayCapacity < 1 || replayCapacity > 100_000) throw new IllegalArgumentException("Invalid replay capacity");
        this.expectedAppId = expectedAppId; this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock; this.skew = skew; this.replayCapacity = replayCapacity;
    }

    public synchronized Claim verify(String appId, String subject, String tenantId, String deviceId, String channelId,
                                     String timestamp, String requestId, String signature) {
        if (blank(appId) || blank(subject) || blank(tenantId) || blank(deviceId) || blank(channelId)
                || blank(timestamp) || blank(requestId) || blank(signature))
            throw new IllegalArgumentException("Incomplete App live claim");
        if (!expectedAppId.equals(appId)) throw new IllegalArgumentException("Unknown App ID");
        long millis;
        try { millis = Long.parseLong(timestamp); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid App claim timestamp"); }
        Instant issued;
        try { issued = Instant.ofEpochMilli(millis); } catch (DateTimeException e) { throw new IllegalArgumentException("Invalid App claim timestamp"); }
        Instant now = clock.instant();
        if (issued.isBefore(now.minus(skew)) || issued.isAfter(now.plus(skew))) throw new IllegalArgumentException("Expired App live claim");
        String canonical = appId + "\n" + subject + "\n" + tenantId + "\n" + deviceId + "\n" + channelId + "\n" + timestamp + "\n" + requestId;
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] actual = Base64.getUrlDecoder().decode(signature);
            if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException("Invalid App live signature");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("App signature verification unavailable", e); }
        purge(now);
        if (seenRequests.containsKey(requestId)) throw new IllegalArgumentException("Replayed App request");
        if (seenRequests.size() >= replayCapacity) throw new IllegalArgumentException("App replay protection capacity reached");
        seenRequests.put(requestId, issued.plus(skew));
        return new Claim(appId, subject, tenantId, deviceId, channelId, requestId);
    }
    private static boolean blank(String value) { return value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl); }
    private void purge(Instant now) {
        Iterator<Map.Entry<String, Instant>> iterator = seenRequests.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue().isBefore(now)) iterator.remove();
    }
}

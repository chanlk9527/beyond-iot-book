package com.nebula.iot.video.api;

/** Trusted integration boundary. Identity must originate from a verified server-side authenticator. */
public interface LiveAccessPolicy {
    enum Audience { MANAGEMENT, APP }
    record Identity(Audience audience, String subject) {
        public Identity {
            if (audience == null || subject == null || subject.isBlank()) throw new IllegalArgumentException("Identity required");
        }
    }
    record Target(String scope, String deviceId, String channelId) {}
    /** Called on creation and every signaling operation. Throw on denial; never default allow. */
    Target requireWatch(Identity identity, String tenantId, String deviceId, String channelId);
}

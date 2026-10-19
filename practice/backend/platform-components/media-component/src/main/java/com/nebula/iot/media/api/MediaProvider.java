package com.nebula.iot.media.api;

import java.time.Instant;
import java.util.List;

/** Provider-neutral viewer signaling. Provider resource IDs never cross the business API. */
public interface MediaProvider {
    Connection open(String resource, String clientId);
    interface Connection extends AutoCloseable {
        List<IceServer> iceServers();
        Instant expiresAt();
        void send(Signal signal);
        /** Nonblocking, ordered destructive read; one consumer per session. */
        List<Signal> drain();
        boolean isOpen();
        void close();
    }
    enum Type { OFFER, ANSWER, ICE }
    record Signal(Type type, String payload) {}
    record IceServer(List<String> urls, String username, String credential) {
        public IceServer { urls = List.copyOf(urls); }
        @Override public String toString() { return "IceServer[redacted]"; }
    }
}

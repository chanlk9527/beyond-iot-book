package com.nebula.iot.video.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.media.api.MediaProvider;
import com.nebula.iot.media.api.MediaProvider.*;
import com.nebula.iot.video.api.LiveAccessPolicy;
import com.nebula.iot.video.api.LiveAccessPolicy.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class LiveSessionServiceTest {
    final Identity alice = new Identity(Audience.MANAGEMENT, "alice");
    final Identity bob = new Identity(Audience.MANAGEMENT, "bob");
    final ObjectMapper json = new ObjectMapper();
    Instant now = Instant.parse("2026-10-18T00:00:00Z");
    Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    };
    boolean allowed = true;
    int opens;
    List<FakeConnection> connections = new ArrayList<>();
    LiveAccessPolicy policy = (actor, tenant, device, channel) -> {
        if (!allowed || actor.audience() == Audience.APP || !"ta".equals(tenant)) throw new AccessDeniedException("denied");
        return new Target(tenant, device, channel);
    };
    MediaProvider provider = (resource, client) -> {
        assertEquals("private-provider-resource", resource); opens++;
        var connection = new FakeConnection(); connections.add(connection); return connection;
    };
    LiveSessionService service;
    @BeforeEach void init() { service = new LiveSessionService(policy, provider,
            List.of(new LiveSessionService.Channel("ta", "d1", "main", "private-provider-resource")), clock, Duration.ofSeconds(60), 2, json); }
    @AfterEach void close() { service.close(); }
    LiveSessionService.View create() { return service.create(alice, "ta", "d1", "main"); }
    @Test void deniesAppCrossTenantAndUnknownChannelBeforeProviderAccess() {
        assertThrows(AccessDeniedException.class, () -> service.create(new Identity(Audience.APP,"alice"), "ta", "d1", "main"));
        assertThrows(AccessDeniedException.class, () -> service.create(alice, "tb", "d1", "main"));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.create(alice,"ta","d1","other")).getStatusCode().value());
        assertEquals(0, opens);
    }
    @Test void cannotReadSendOrDeleteAnotherAccountsSession() {
        var id = create().sessionId();
        assertThrows(ResponseStatusException.class, () -> service.receive(bob, id));
        assertThrows(ResponseStatusException.class, () -> service.send(bob, id, null));
        assertThrows(ResponseStatusException.class, () -> service.delete(bob, id));
        assertTrue(connections.getFirst().open);
    }
    @Test void revocationClosesConnectionOnNextRequest() {
        var id = create().sessionId(); allowed = false;
        assertThrows(AccessDeniedException.class, () -> service.receive(alice, id));
        assertFalse(connections.getFirst().open);
    }
    @Test void cleanupRechecksAuthorizationWithoutClientTraffic() {
        create(); allowed = false; service.sweep(); assertFalse(connections.getFirst().open);
    }
    @Test void expiryReleasesCapacityAndDeleteIsIdempotent() {
        var id = create().sessionId(); create();
        assertEquals(429, assertThrows(ResponseStatusException.class, this::create).getStatusCode().value());
        now = now.plusSeconds(61);
        assertEquals(410, assertThrows(ResponseStatusException.class, () -> service.receive(alice,id)).getStatusCode().value());
        create(); assertFalse(connections.getFirst().open);
        service.delete(alice,id); service.delete(alice,id);
    }
    @Test void offerAndIceForwardWithoutAwsEnvelope() throws Exception {
        var view = create();
        assertFalse(json.writeValueAsString(view.iceServers()).contains("private-provider-resource"));
        service.send(alice, view.sessionId(), new Signal(Type.OFFER, json.writeValueAsString(Map.of("type","offer","sdp","v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n"))));
        service.send(alice, view.sessionId(), new Signal(Type.ICE, "{\"candidate\":\"candidate:1\",\"sdpMid\":\"0\"}"));
        assertEquals(2, connections.getFirst().sent.size());
        connections.getFirst().incoming.add(new Signal(Type.ANSWER,"{\"type\":\"answer\",\"sdp\":\"v=0\"}"));
        assertEquals(Type.ANSWER, service.receive(alice, view.sessionId()).getFirst().type());
        assertTrue(service.receive(alice,view.sessionId()).isEmpty());
        allowed = false; service.delete(alice, view.sessionId()); assertFalse(connections.getFirst().open);
    }
    @Test void rejectsSendingMediaDataChannelsAndMalformedSignals() throws Exception {
        var id = create().sessionId();
        for (String sdp : List.of("v=0", "m=audio 9 UDP/TLS/RTP/SAVPF 0\na=sendrecv", "m=video 9 UDP/TLS/RTP/SAVPF 96", "m=video 9 UDP/TLS/RTP/SAVPF 96\na=sendonly\na=recvonly",
                "m=video 9 UDP/TLS/RTP/SAVPF 96\na=recvonly\nm=audio 9 UDP/TLS/RTP/SAVPF 0\na=sendonly",
                "a=recvonly\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel")) {
            String payload = json.writeValueAsString(Map.of("type","offer","sdp",sdp));
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.send(alice,id,new Signal(Type.OFFER,payload))).getStatusCode().value());
        }
        assertThrows(ResponseStatusException.class, () -> service.send(alice,id,new Signal(Type.ANSWER,"{}")));
        assertThrows(ResponseStatusException.class, () -> service.send(alice,id,new Signal(Type.ICE,"null")));
        assertTrue(connections.getFirst().sent.isEmpty());
    }
    @Test void acceptsSessionDirectionAndMediaOverride() {
        LiveSessionService.requireReceiveOnly("a=recvonly\nm=audio 9 UDP/TLS/RTP/SAVPF 0\nm=video 9 UDP/TLS/RTP/SAVPF 96\na=inactive");
    }
    @Test void providerFailureIsSanitized() {
        var failing = new LiveSessionService(policy,(r,c) -> { throw new IllegalStateException("arn:secret?X-Amz-Signature=secret"); },
                List.of(new LiveSessionService.Channel("ta","d1","main","private-provider-resource")),clock,Duration.ofSeconds(60),2,json);
        var error = assertThrows(ResponseStatusException.class, () -> failing.create(alice,"ta","d1","main"));
        assertEquals(503,error.getStatusCode().value()); assertFalse(error.toString().contains("secret"));
    }
    @Test void rejectsDuplicateResourcesAcrossDevices() {
        assertThrows(IllegalArgumentException.class, () -> new LiveSessionService(policy,provider,List.of(
            new LiveSessionService.Channel("ta","d1","main","same"),new LiveSessionService.Channel("tb","d2","main","same")),clock,Duration.ofSeconds(60),2,json));
    }
    class FakeConnection implements Connection {
        boolean open = true;
        List<Signal> incoming = new ArrayList<>(), sent = new ArrayList<>();
        public List<IceServer> iceServers() { return List.of(new IceServer(List.of("turn:example.test"),"user","secret")); }
        public Instant expiresAt() { return now.plusSeconds(120); }
        public void send(Signal signal) { sent.add(signal); }
        public List<Signal> drain() { var result = List.copyOf(incoming); incoming.clear(); return result; }
        public boolean isOpen() { return open; }
        public void close() { open = false; }
    }
}

package com.nebula.iot.media.kvs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.media.api.MediaProvider;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesisvideo.KinesisVideoClient;
import software.amazon.awssdk.services.kinesisvideo.model.*;
import software.amazon.awssdk.services.kinesisvideosignaling.KinesisVideoSignalingClient;

/** KVS control plane + backend-only viewer WebSocket. Does not proxy media packets. */
public final class KvsMediaProvider implements MediaProvider, AutoCloseable {
    private final Region region;
    private final AwsCredentialsProvider credentials;
    private final KinesisVideoClient control;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Set<Relay> active = ConcurrentHashMap.newKeySet();
    public KvsMediaProvider(String region, AwsCredentialsProvider credentials, ObjectMapper json) {
        this.region = Region.of(region); this.credentials = credentials; this.json = json;
        control = KinesisVideoClient.builder().region(this.region).credentialsProvider(credentials)
                .overrideConfiguration(timeouts()).build();
    }
    private static ClientOverrideConfiguration timeouts() {
        return ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(15))
                .apiCallAttemptTimeout(Duration.ofSeconds(8)).build();
    }
    @Override public Connection open(String resource, String clientId) {
        Relay relay = null;
        try {
            var endpoints = control.getSignalingChannelEndpoint(r -> r.channelARN(resource)
                    .singleMasterChannelEndpointConfiguration(c -> c.role(ChannelRole.VIEWER)
                            .protocols(ChannelProtocol.WSS, ChannelProtocol.HTTPS))).resourceEndpointList();
            String https = endpoints.stream().filter(e -> e.protocol() == ChannelProtocol.HTTPS)
                    .findFirst().orElseThrow().resourceEndpoint();
            String wss = endpoints.stream().filter(e -> e.protocol() == ChannelProtocol.WSS)
                    .findFirst().orElseThrow().resourceEndpoint();
            List<IceServer> ice = new ArrayList<>();
            Instant expiry = Instant.now().plusSeconds(300);
            // TURN is mandatory here: fail closed rather than return an untested region-specific STUN URL.
            try (var signaling = KinesisVideoSignalingClient.builder().region(region).credentialsProvider(credentials)
                    .endpointOverride(URI.create(https)).overrideConfiguration(timeouts()).build()) {
                var result = signaling.getIceServerConfig(r -> r.channelARN(resource).clientId(clientId));
                for (var server : result.iceServerList()) {
                    ice.add(new IceServer(server.uris(), server.username(), server.password()));
                    expiry = expiry.isBefore(Instant.now().plusSeconds(server.ttl())) ? expiry : Instant.now().plusSeconds(server.ttl());
                }
            }
            if (ice.isEmpty()) throw new IllegalStateException("No ICE configuration");
            relay = new Relay(ice, expiry.minusSeconds(5));
            active.add(relay);
            var socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(signedUri(wss, resource, clientId), relay).get(12, TimeUnit.SECONDS);
            relay.socket = socket;
            if (!relay.isOpen()) throw new IllegalStateException("Signaling closed during connect");
            return relay;
        } catch (Exception failure) {
            if (relay != null) relay.close();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // Signed URI, channel ARN and provider exceptions must not reach the public problem body.
            throw new IllegalStateException("Media provider unavailable");
        }
    }
    URI signedUri(String endpoint, String resource, String clientId) {
        URI uri = URI.create(endpoint);
        if (!"wss".equals(uri.getScheme()) || uri.getRawQuery() != null) throw new IllegalArgumentException("Secure endpoint required");
        var request = SdkHttpFullRequest.builder().uri(URI.create("https" + endpoint.substring(3)))
                .method(SdkHttpMethod.GET).putRawQueryParameter("X-Amz-ChannelARN", resource)
                .putRawQueryParameter("X-Amz-ClientId", clientId).build();
        var signed = Aws4Signer.create().presign(request, Aws4PresignerParams.builder()
                .awsCredentials(credentials.resolveCredentials()).signingName("kinesisvideo").signingRegion(region)
                .expirationTime(Instant.now().plusSeconds(299)).build());
        return URI.create("wss" + signed.getUri().toString().substring(5));
    }
    @Override public void close() {
        List.copyOf(active).forEach(Relay::close);
        control.close(); http.close();
        if (credentials instanceof AutoCloseable owned) {
            try { owned.close(); } catch (Exception ignored) { /* Best-effort credential provider cleanup. */ }
        }
    }
    private final class Relay implements Connection, WebSocket.Listener {
        private final List<IceServer> ice;
        private final Instant expires;
        private final ArrayBlockingQueue<Signal> incoming = new ArrayBlockingQueue<>(256);
        private final StringBuilder fragments = new StringBuilder();
        private volatile WebSocket socket;
        private volatile boolean closed;
        Relay(List<IceServer> ice, Instant expires) { this.ice = List.copyOf(ice); this.expires = expires; }
        @Override public List<IceServer> iceServers() { return ice; }
        @Override public Instant expiresAt() { return expires; }
        @Override public boolean isOpen() { return !closed; }
        @Override public synchronized void send(Signal signal) {
            if (closed || socket == null) throw new IllegalStateException("Signaling closed");
            String action = switch (signal.type()) { case OFFER -> "SDP_OFFER"; case ICE -> "ICE_CANDIDATE";
                default -> throw new IllegalArgumentException("Viewer sends offer and ICE only"); };
            try {
                String encoded = Base64.getEncoder().encodeToString(signal.payload().getBytes(StandardCharsets.UTF_8));
                if (encoded.length() > 10000) throw new IllegalArgumentException("Signaling payload too large");
                socket.sendText(json.writeValueAsString(Map.of("action", action, "messagePayload", encoded,
                        "correlationId", UUID.randomUUID().toString())), true)
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                close(); throw new IllegalStateException("Signaling send failed");
            }
        }
        @Override public List<Signal> drain() { var result = new ArrayList<Signal>(); incoming.drainTo(result); return result; }
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            try {
                if (fragments.length() + text.length() > 65536) throw new IllegalArgumentException();
                fragments.append(text);
                if (last) {
                    var message = json.readTree(fragments.toString()); fragments.setLength(0);
                    String type = message.path("messageType").asText();
                    if ("SDP_ANSWER".equals(type) || "ICE_CANDIDATE".equals(type)) {
                        String encoded = message.path("messagePayload").asText();
                        if (encoded.length() > 10000) throw new IllegalArgumentException();
                        String payload = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                        if (!incoming.offer(new Signal("SDP_ANSWER".equals(type) ? Type.ANSWER : Type.ICE, payload))) throw new IllegalStateException();
                    } else if ("STATUS_RESPONSE".equals(type) || "GO_AWAY".equals(type) || "RECONNECT_ICE_SERVER".equals(type)) {
                        // No silent reconnect: client creates a fresh authorized session and ICE config.
                        close();
                    }
                }
            } catch (Exception e) { close(); }
            ws.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { close(); return CompletableFuture.completedFuture(null); }
        @Override public void onError(WebSocket ws, Throwable failure) { close(); }
        @Override public synchronized void close() {
            closed = true; incoming.clear(); active.remove(this);
            if (socket != null) socket.abort();
        }
    }
}

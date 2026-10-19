package com.nebula.iot.bootstrap.subscription;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class SubscriptionConfiguration implements WebSocketConfigurer {
    private final SubscriptionGateway gateway;
    public SubscriptionConfiguration(SubscriptionGateway gateway) { this.gateway = gateway; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Spring's default same-origin check is retained. No wildcard origin or SockJS fallback.
        registry.addHandler(gateway, "/api/management/realtime/socket").addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Map<String, Object> attributes) {
                try {
                    var servlet = (ServletServerHttpRequest) request;
                    HttpSession session = servlet.getServletRequest().getSession(false);
                    var token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
                    var grant = gateway.consume(token, request.getPrincipal(), session);
                    if (grant != null) { attributes.put("grant", grant); return true; }
                } catch (RuntimeException denied) { /* Fail closed, without exposing resource details. */ }
                response.setStatusCode(HttpStatus.FORBIDDEN); return false;
            }
            @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Exception exception) { }
        });
    }
}

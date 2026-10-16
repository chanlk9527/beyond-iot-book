package com.nebula.iot.bootstrap.management;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class CsrfController {
    public record Token(String headerName, String token) {}
    @GetMapping("/api/management/auth/csrf")
    public Token csrf(CsrfToken token) { return new Token(token.getHeaderName(), token.getToken()); }
}

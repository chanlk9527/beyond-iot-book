package com.nebula.iot.bootstrap.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.identity.api.AccountDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Session identity is rechecked so disabled/deleted accounts lose API access immediately. */
final class AccountRevalidationFilter extends OncePerRequestFilter {
    private final AccountDirectory accounts;
    private final ObjectMapper mapper;
    AccountRevalidationFilter(AccountDirectory accounts, ObjectMapper mapper) {
        this.accounts = accounts;
        this.mapper = mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/management/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            try {
                accounts.requireEnabled(authentication.getName());
            } catch (AuthenticationException unavailable) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityProblemWriter.write(mapper, response, HttpStatus.UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

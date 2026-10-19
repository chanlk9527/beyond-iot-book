package com.nebula.iot.bootstrap.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.identity.application.OpenApiService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** External callers use a stateless chain and never inherit management login sessions. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class OpenApiSecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain openApiSecurity(HttpSecurity http, OpenApiService service, ObjectMapper json) throws Exception {
        return http.securityMatcher("/api/open/**")
            .csrf(csrf->csrf.disable())
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache->cache.disable())
            .authorizeHttpRequests(auth->auth
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/open/v1/oauth/token").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((req,res,e)->unauthorized(res,json))
                .accessDeniedHandler((req,res,e)->SecurityProblemWriter.write(json,res,HttpStatus.FORBIDDEN)))
            .addFilterBefore(new BearerFilter(service,json), AnonymousAuthenticationFilter.class).build();
    }
    private static void unauthorized(HttpServletResponse response,ObjectMapper json) throws IOException {
        response.setHeader("WWW-Authenticate","Bearer");
        SecurityProblemWriter.write(json,response,HttpStatus.UNAUTHORIZED);
    }
    static final class BearerFilter extends OncePerRequestFilter {
        private final OpenApiService service; private final ObjectMapper json;
        BearerFilter(OpenApiService service,ObjectMapper json){this.service=service;this.json=json;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            response.setHeader("Cache-Control","no-store");
            if (request.getRequestURI().equals(request.getContextPath()+"/api/open/v1/oauth/token")) {chain.doFilter(request,response);return;}
            var context=SecurityContextHolder.createEmptyContext();
            try {
                String header=request.getHeader("Authorization");
                if(header==null||!header.startsWith("Bearer "))throw new BadCredentialsException("Bearer required");
                var principal=service.authenticate(header.substring(7),null);
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,
                    principal.scopes().stream().map(s->new SimpleGrantedAuthority("SCOPE_"+s)).toList()));
            } catch(AuthenticationException denied){unauthorized(response,json);return;}
            SecurityContextHolder.setContext(context);
            try {chain.doFilter(request,response);} finally {SecurityContextHolder.clearContext();}
        }
    }
}

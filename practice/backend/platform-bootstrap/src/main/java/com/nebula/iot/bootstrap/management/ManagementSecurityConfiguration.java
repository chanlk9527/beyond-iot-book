package com.nebula.iot.bootstrap.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.identity.api.AccountDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
@EnableMethodSecurity
public class ManagementSecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean SecurityFilterChain managementSecurityFilterChain(HttpSecurity http, ObjectMapper mapper,
                                                           AccountDirectory accounts) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                                "/api/management/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/management/auth/login").permitAll()
                        .requestMatchers("/api/app/video/**").permitAll()
                        .requestMatchers("/api/management/**").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/app/video/**"))
                .formLogin(login -> login.loginPage("/api/management/auth/login").loginProcessingUrl("/api/management/auth/login")
                        .successHandler((request, response, auth) -> response.setStatus(204))
                        .failureHandler((request, response, exception) ->
                                SecurityProblemWriter.write(mapper, response, HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout.logoutUrl("/api/management/auth/logout")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                SecurityProblemWriter.write(mapper, response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                SecurityProblemWriter.write(mapper, response, HttpStatus.FORBIDDEN)))
                .addFilterBefore(new AccountRevalidationFilter(accounts, mapper), AuthorizationFilter.class)
                .build();
    }
}

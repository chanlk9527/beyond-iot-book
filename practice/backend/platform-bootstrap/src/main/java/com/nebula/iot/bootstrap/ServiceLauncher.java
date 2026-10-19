package com.nebula.iot.bootstrap;

import com.nebula.iot.PlatformApplication;
import java.util.Map;
import org.springframework.boot.SpringApplication;

/** Shared composition; independent launchers reuse domain code and enforce their runtime identity. */
public final class ServiceLauncher {
    private ServiceLauncher() { }
    public static SpringApplication application(String role) {
        int port = switch (role) {
            case "management" -> 8080;
            case "access" -> 8081;
            case "worker" -> 8082;
            default -> throw new IllegalArgumentException("Unknown service: " + role);
        };
        var application = new SpringApplication(PlatformApplication.class);
        application.setAdditionalProfiles("management", "cluster", "service");
        application.setDefaultProperties(Map.of("nebula.runtime.service", role, "nebula.service.http-port", port));
        return application;
    }
}
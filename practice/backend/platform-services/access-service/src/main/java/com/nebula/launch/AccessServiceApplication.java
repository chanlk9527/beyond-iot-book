package com.nebula.launch;

import com.nebula.iot.bootstrap.ServiceLauncher;
import org.springframework.boot.SpringApplication;

public final class AccessServiceApplication {
    private AccessServiceApplication() { }
    public static SpringApplication application() { return ServiceLauncher.application("access"); }
    public static void main(String[] args) { application().run(args); }
}
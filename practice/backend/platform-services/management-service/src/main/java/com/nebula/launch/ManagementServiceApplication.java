package com.nebula.launch;

import com.nebula.iot.bootstrap.ServiceLauncher;
import org.springframework.boot.SpringApplication;

public final class ManagementServiceApplication {
    private ManagementServiceApplication() { }
    public static SpringApplication application() { return ServiceLauncher.application("management"); }
    public static void main(String[] args) { application().run(args); }
}
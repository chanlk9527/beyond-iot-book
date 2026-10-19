package com.nebula.launch;

import com.nebula.iot.bootstrap.ServiceLauncher;
import org.springframework.boot.SpringApplication;

public final class WorkerServiceApplication {
    private WorkerServiceApplication() { }
    public static SpringApplication application() { return ServiceLauncher.application("worker"); }
    public static void main(String[] args) { application().run(args); }
}
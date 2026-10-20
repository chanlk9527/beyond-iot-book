package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.PlatformApplication;
import com.nebula.iot.cluster.AccessNodeAgent;
import java.util.ArrayList;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import com.nebula.iot.network.mqtt.VertxMqttGateway;

/** Test child JVM: real Boot process and MQTT listener, sharing only the test TCP database. */
public final class ClusterNodeProcess {
    public static void main(String[] args) {
        var options=new ArrayList<String>(java.util.List.of(
                "--spring.profiles.active=management,cluster", "--spring.datasource.url="+args[0],
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
                "--spring.datasource.password=test", "--DATABASE_PASSWORD=test", "--server.port=0",
                "--nebula.runtime.role="+(args.length>2?args[2]:"access"), "--nebula.access.node-name="+args[1], "--nebula.mqtt.enabled=true", "--nebula.mqtt.port=0",
                "--nebula.mqtt.development-mode=true", "--nebula.cluster.lease="+(args.length>3?"PT15S":"PT6S"),
                "--nebula.management.initial-account.username=cluster-admin",
                "--nebula.management.initial-account.password=test-password",
                "--nebula.management.initial-account.platform-admin=true"));
        if(args.length>3) {
            options.add("--nebula.cluster.commands.enabled=true");options.add("--nebula.eventbus.mode="+(args[3].equals("dubbo")?"dubbo":"kafka"));
            if(args[3].equals("dubbo")) {
                try(var socket=new java.net.ServerSocket(0)) { options.add("--nebula.eventbus.dubbo.port="+socket.getLocalPort()); }
                catch(java.io.IOException error){throw new IllegalStateException(error);}
                options.add("--nebula.eventbus.dubbo.host=127.0.0.1");options.add("--nebula.eventbus.dubbo.discovery=jdbc");
                options.add("--nebula.eventbus.dubbo.credential=isolated-test-only-dubbo-credential-32");
            }
            options.add("--spring.kafka.bootstrap-servers="+args[3]);
            options.add("--nebula.eventbus.topic=commands-test");options.add("--nebula.eventbus.failure-topic=commands-failed");
            options.add("--nebula.eventbus.namespace=commands-test");
        }
        for(int i=1;i<=8;i++) {
            String prefix="--nebula.mqtt.devices["+(i-1)+"].";
            options.add(prefix+"tenant-id=test-tenant");options.add(prefix+"product-id=warmer");
            options.add(prefix+"device-id=warmer-"+i);options.add(prefix+"client-id=client-"+i);
            options.add(prefix+"username=user-"+i);options.add(prefix+"password=test-password");
        }
        var context=SpringApplication.run(PlatformApplication.class,options.toArray(String[]::new));
        var agent=context.getBeanProvider(AccessNodeAgent.class).getIfAvailable();
        var gateway=context.getBeanProvider(VertxMqttGateway.class).getIfAvailable();
        int http=((ServletWebServerApplicationContext)context).getWebServer().getPort();
        System.out.println("CLUSTER_READY "+(agent==null?"-":agent.nodeId())+" "+(gateway==null?-1:gateway.actualPort())+" "+http);
        System.out.flush();
    }
}

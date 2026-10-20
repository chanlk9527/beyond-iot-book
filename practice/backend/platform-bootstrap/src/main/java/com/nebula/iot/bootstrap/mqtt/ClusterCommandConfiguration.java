package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.device.api.DurableCommands;
import com.nebula.iot.device.infrastructure.JdbcDurableCommands;
import com.nebula.iot.gateway.JdbcDeviceSessionDirectory;
import com.nebula.iot.message.*;
import com.nebula.iot.network.mqtt.VertxMqttGateway;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.cluster.commands.enabled",havingValue="true")
public class ClusterCommandConfiguration {
    @Bean DurableCommands durableCommands(DataSource source,PlatformTransactionManager manager,EventBus bus,
            ObjectProvider<JdbcDeviceSessionDirectory> directory) {
        if(directory.getIfAvailable()==null || !bus.supportsCluster() || !bus.supportsDurablePublish())throw new IllegalStateException("Durable cluster commands require membership and durable event delivery");
        var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);
        var tx=new TransactionTemplate(manager);tx.setTimeout(5);
        return new JdbcDurableCommands(jdbc,tx);
    }

    @Bean(destroyMethod="close")
    @ConditionalOnExpression("${nebula.runtime.worker:true}")
    Subscription commandRoutingSubscription(DurableCommands commands,JdbcDeviceSessionDirectory directory,EventBus bus) {
        return bus.subscribe(SubscriptionSpec.shared("command-route-v1","internal/command/route"),event->{
            var dispatch=validated(commands,event);
            if(dispatch!=null) {
                var owner=directory.owner(dispatch.command().device());
                commands.route(event.messageId(),owner==null?null:new DurableCommands.Target(owner.nodeId(),owner.sessionId(),owner.epoch()));
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="nebula.mqtt.enabled",havingValue="true",matchIfMissing=true)
    Subscription commandDispatchSubscription(DurableCommands commands,JdbcDeviceSessionDirectory directory,EventBus bus,
            VertxMqttGateway gateway,AccessClusterConfiguration.Instance instance) {
        return bus.subscribe(SubscriptionSpec.broadcast("command-dispatch-v1","internal/command/dispatch"),event->{
            var dispatch=validated(commands,event);
            if(dispatch==null || dispatch.target()==null || !dispatch.target().nodeId().equals(instance.id()))return CompletableFuture.completedFuture(null);
            var owner=directory.owner(dispatch.command().device());
            if(owner==null || !dispatch.target().sessionId().equals(owner.sessionId()) || dispatch.target().epoch()!=owner.epoch()) {
                commands.reroute(event.messageId(),dispatch.target(),dispatch.attempt());return CompletableFuture.completedFuture(null);
            }
            if(!commands.startSending(event.messageId(),dispatch.target(),dispatch.attempt()))return CompletableFuture.completedFuture(null);
            // Claim is durable before send. A crash here leaves UNKNOWN/TIMED_OUT and never triggers a second send.
            return gateway.sendToSession(dispatch.command(),dispatch.target().sessionId(),dispatch.target().epoch())
                    .handle((receipt,error)->{commands.delivered(event.messageId(),error==null);return (Void)null;});
        });
    }

    private static DurableCommands.Dispatch validated(DurableCommands commands,EventMessage event) {
        if(!(event.payload() instanceof Map<?,?> value) || !Objects.equals(value.get("schemaVersion"),1))throw new IllegalArgumentException("Unsupported command signal");
        var pending=commands.pending(event.messageId());
        if(pending!=null && (!pending.command().device().tenantId().value().equals(event.tenantId()) || !pending.command().device().deviceId().value().equals(event.deviceId())))
            throw new SecurityException("Command envelope identity mismatch");
        return pending;
    }

    @Bean @ConditionalOnExpression("${nebula.runtime.worker:true}")
    SmartLifecycle commandOutboxRelay(DurableCommands commands,EventBus bus,JdbcDeviceSessionDirectory directory) {
        return new SmartLifecycle() {
            private ScheduledExecutorService timer;
            private volatile boolean running;
            public int getPhase(){return 200;}
            public boolean isRunning(){return running;}
            public void start(){
                running=true;
                timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("command-outbox-relay").factory());
                timer.scheduleWithFixedDelay(()->{
                    try {
                        commands.sweep();
                        commands.recoverRoutes(key->{var owner=directory.owner(key);return owner==null?null:new DurableCommands.Target(owner.nodeId(),owner.sessionId(),owner.epoch());});
                        for(var event:commands.outgoing(20)) {
                            if(!running)return;
                            var now=Instant.now();
                            var envelope=new EventMessage(event.eventId(),event.topic(),now,now,event.tenantId(),event.deviceId(),"v1",event.messageId(),"APPLICATION",Map.of(),Map.of("schemaVersion",1));
                            var result=bus.publish(envelope).toCompletableFuture().get(5,TimeUnit.SECONDS);
                            if(!result.durable())throw new IllegalStateException("Durable event acceptance required");
                            commands.published(event.eventId());
                        }
                    }catch(Exception error){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Command relay will retry durable pending work",error);}
                },0,250,TimeUnit.MILLISECONDS);
            }
            public void stop(){running=false;if(timer!=null)timer.shutdownNow();}
        };
    }
}

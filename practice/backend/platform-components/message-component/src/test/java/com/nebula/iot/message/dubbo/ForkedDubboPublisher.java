package com.nebula.iot.message.dubbo;

import org.h2.jdbcx.JdbcDataSource;
import java.time.*;
import java.util.concurrent.TimeUnit;

public final class ForkedDubboPublisher {
    public static void main(String[] args) throws Exception {
        var ds=new JdbcDataSource();ds.setURL(args[0]);ds.setUser("sa");
        EventNodeDiscovery discovery=args.length<3?null:new NacosEventNodeDiscovery(args[2],args[4],"BEYOND_IOT_TEST",args[3],
                System.getenv("NACOS_TEST_USERNAME"),System.getenv("NACOS_TEST_PASSWORD"),"test");
        try(var bus=new DubboEventBus(DubboEventBusTest.routes(ds),"test","fork-publisher","127.0.0.1",Integer.parseInt(args[1]),DubboEventBusTest.TOKEN,Duration.ofSeconds(2),1000,discovery)) {
            bus.start();bus.publish(DubboEventBusTest.event("cross-jvm","t1","d1")).toCompletableFuture().get(10,TimeUnit.SECONDS);
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while((bus.pendingCount()!=0 || bus.sentRpcCount()==0) && System.nanoTime()<deadline)Thread.sleep(50);
            if(bus.pendingCount()!=0)throw new IllegalStateException("Delivery did not complete");
            if(bus.sentRpcCount()==0)throw new IllegalStateException("Publisher did not send across the JVM boundary");
        }
    }
}

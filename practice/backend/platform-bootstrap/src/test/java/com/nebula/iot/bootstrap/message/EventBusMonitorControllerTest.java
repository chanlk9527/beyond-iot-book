package com.nebula.iot.bootstrap.message;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.message.dubbo.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventBusMonitorControllerTest {
    @Test void monitoringUsesCurrentDiscoveredInstancesIncludingAnEmptyRegistry() {
        var accounts=mock(AccountDirectory.class);var store=mock(JdbcEventRoutes.class);var bus=mock(DubboEventBus.class);
        var current=Set.of("replacement");
        when(bus.discoveredNodeIds()).thenReturn(Optional.of(current));
        when(store.overview(current)).thenReturn(new JdbcEventRoutes.DirectoryView("test",8,0,0,0,Instant.now(),List.of(),new JdbcEventRoutes.Backlog(0,0,0,null)));
        var beans=new StaticListableBeanFactory();beans.addBean("routes",store);beans.addBean("bus",bus);
        try(var controller=new EventBusMonitorController(accounts,beans.getBeanProvider(JdbcEventRoutes.class),beans.getBeanProvider(DubboEventBus.class),"dubbo")) {
            assertTrue(controller.snapshot(()->"admin").getBody().complete());
            verify(store).overview(current);verify(store,never()).overview();
        }
        when(bus.discoveredNodeIds()).thenReturn(Optional.of(Set.of()));
        when(store.overview(Set.of())).thenReturn(new JdbcEventRoutes.DirectoryView("test",8,0,0,0,Instant.now(),List.of(),new JdbcEventRoutes.Backlog(0,0,0,null)));
        try(var controller=new EventBusMonitorController(accounts,beans.getBeanProvider(JdbcEventRoutes.class),beans.getBeanProvider(DubboEventBus.class),"dubbo")) {
            assertTrue(controller.snapshot(()->"admin").getBody().nodes().isEmpty());
            verify(store).overview(Set.of());verify(store,never()).overview();
        }
    }
    @Test void authorizationPrecedesCachedReadsAndUnsupportedModeHasNoInventedMetrics() {
        var accounts=mock(AccountDirectory.class);var beans=new StaticListableBeanFactory();
        try(var controller=new EventBusMonitorController(accounts,beans.getBeanProvider(JdbcEventRoutes.class),beans.getBeanProvider(DubboEventBus.class),"local")) {
            var snapshot=controller.snapshot(()->"admin").getBody();
            assertEquals("local",snapshot.mode());assertNull(snapshot.directory());assertFalse(snapshot.complete());
            doThrow(new SecurityException()).when(accounts).requirePlatformAdmin("tenant");
            assertThrows(SecurityException.class,()->controller.snapshot(()->"tenant"));
            assertThrows(SecurityException.class,()->controller.subscriptions(()->"tenant",0));
            assertThrows(SecurityException.class,()->controller.backlog(()->"tenant","a".repeat(64)));
        }
    }
    @Test void disconnectedDiscoveryDoesNotFallBackToHistoricalDirectory() {
        var accounts=mock(AccountDirectory.class);var store=mock(JdbcEventRoutes.class);var bus=mock(DubboEventBus.class);
        when(bus.discoveredNodeIds()).thenThrow(new IllegalStateException("EventBus discovery unavailable"));
        var beans=new StaticListableBeanFactory();beans.addBean("routes",store);beans.addBean("bus",bus);
        try(var controller=new EventBusMonitorController(accounts,beans.getBeanProvider(JdbcEventRoutes.class),beans.getBeanProvider(DubboEventBus.class),"dubbo")) {
            assertThrows(IllegalStateException.class,()->controller.snapshot(()->"admin"));
            verifyNoInteractions(store);
        }
    }
    @Test void unreachableNodeIsUnknownAndRepeatedReadsReuseTheSample() {
        var accounts=mock(AccountDirectory.class);var store=mock(JdbcEventRoutes.class);var bus=mock(DubboEventBus.class);
        var now=Instant.now();
        var node=new JdbcEventRoutes.ObservedNode("remote","127.0.0.1:1","READY",now.plusSeconds(10),7,true);
        when(store.overview()).thenReturn(new JdbcEventRoutes.DirectoryView("test",8,1,1,0,now,List.of(node),new JdbcEventRoutes.Backlog(0,0,0,null)));
        when(bus.inspectNode(node.id(),node.endpoint())).thenThrow(new IllegalStateException("connection refused"));
        var beans=new StaticListableBeanFactory();beans.addBean("routes",store);beans.addBean("bus",bus);
        try(var controller=new EventBusMonitorController(accounts,beans.getBeanProvider(JdbcEventRoutes.class),beans.getBeanProvider(DubboEventBus.class),"dubbo")) {
            var snapshot=controller.snapshot(()->"admin").getBody();
            assertFalse(snapshot.complete());assertEquals(0,snapshot.collected());
            assertNull(snapshot.nodes().getFirst().metrics());assertEquals("UNREACHABLE",snapshot.nodes().getFirst().observation());
            assertSame(snapshot,controller.snapshot(()->"admin").getBody());
            verify(store,times(1)).overview();verify(accounts,times(2)).requirePlatformAdmin("admin");
        }
    }
}

package com.nebula.iot.message.dubbo;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosEventNodeDiscoveryTest {
    static Instance instance(String node, String namespace, int port) {
        var result=new Instance(); result.setIp("127.0.0.1"); result.setPort(port);
        result.setMetadata(Map.of(NacosEventNodeDiscovery.NODE,node,NacosEventNodeDiscovery.NAMESPACE,namespace,"eventbus.protocol","dubbo"));
        return result;
    }
    @Test void registersOnlyInstanceMetadataAndAppliesPushedHealthyAddresses() throws Exception {
        var naming=mock(NamingService.class);
        when(naming.getServerStatus()).thenReturn("UP");
        when(naming.getAllInstances("events","group",false)).thenReturn(List.of(),List.of(instance("self","test",20880)));
        var properties=new Properties();
        var discovery=new NacosEventNodeDiscovery("server:8848","registry-namespace","group","events","user","secret","test", p->{properties.putAll(p);return naming;});
        discovery.start("self","127.0.0.1",20880,"worker");
        assertEquals("registry-namespace",properties.getProperty("namespace"));
        assertEquals("secret",properties.getProperty("password"));
        var registered=ArgumentCaptor.forClass(Instance.class);
        verify(naming).registerInstance(eq("events"),eq("group"),registered.capture());
        assertEquals(Set.of("eventbus.nodeId","eventbus.namespace","eventbus.role","eventbus.protocol"),registered.getValue().getMetadata().keySet());
        assertTrue(registered.getValue().isEphemeral());
        var listener=ArgumentCaptor.forClass(EventListener.class);
        verify(naming).subscribe(eq("events"),eq("group"),listener.capture());
        var unhealthy=instance("bad","test",20884);unhealthy.setHealthy(false);
        var disabled=instance("disabled","test",20885);disabled.setEnabled(false);
        listener.getValue().onEvent(new NamingEvent("events",List.of(instance("self","test",20880),instance("peer","test",20882),instance("foreign","other",20883),unhealthy,disabled)));
        assertEquals(Map.of("self","127.0.0.1:20880","peer","127.0.0.1:20882"),discovery.endpoints());
        listener.getValue().onEvent(new NamingEvent("events",List.of(instance("self","test",20880))));
        assertFalse(discovery.endpoints().containsKey("peer"));
        listener.getValue().onEvent(new NamingEvent("events",List.of(instance("self","test",20880),instance("same","test",20881),instance("same","test",20882))));
        assertFalse(discovery.endpoints().containsKey("same"));
        when(naming.getServerStatus()).thenReturn("DOWN");assertFalse(discovery.connected());
        discovery.close(); discovery.close();
        verify(naming).deregisterInstance(eq("events"),eq("group"),any(Instance.class));
        verify(naming,times(1)).shutDown();
        listener.getValue().onEvent(new NamingEvent("events",List.of(instance("peer","test",20882))));
        assertTrue(discovery.endpoints().isEmpty());
    }
    @Test void registrationFailureClosesTheClientWithoutFallingBackOrExposingCredentials() throws Exception {
        var naming=mock(NamingService.class);
        doThrow(new NacosException(403,"secret-password")).when(naming).registerInstance(anyString(),anyString(),any(Instance.class));
        var discovery=new NacosEventNodeDiscovery("server:8848","","group","events","user","secret-password","test", p->naming);
        var failure=assertThrows(IllegalStateException.class,()->discovery.start("self","127.0.0.1",20880,"worker"));
        assertFalse(failure.toString().contains("secret-password"));assertNull(failure.getCause());
        verify(naming).shutDown();assertFalse(discovery.connected());assertTrue(discovery.endpoints().isEmpty());
    }
}

package com.nebula.iot.bootstrap.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.device.api.CatalogViews.Device;
import com.nebula.iot.message.*;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionGatewayTest {
    CatalogService catalog;
    SubscriptionGateway gateway;
    LocalEventBus bus;
    HttpSession session;
    @BeforeEach void setup() {
        catalog=mock(CatalogService.class); bus=new LocalEventBus(Runnable::run,Duration.ofSeconds(1));
        gateway=new SubscriptionGateway(catalog,new ObjectMapper().findAndRegisterModules(),bus);
        session=mock(HttpSession.class); when(session.getId()).thenReturn("session");
        when(session.getLastAccessedTime()).thenReturn(System.currentTimeMillis());
        when(catalog.device("alice","ta","d1")).thenReturn(new Device("d1","ta","p","Product","code","Device","v1","ACTIVE",1,1));
    }
    @AfterEach void stop() { gateway.shutdown(); }
    @Test void ticketsAreSingleUseBoundToSessionAndActorAndResourceChecked() {
        var resources=List.of(new SubscriptionGateway.Resource("ta","d1"));
        var ticket=gateway.issue("alice",session,resources);
        assertNotNull(gateway.consume(ticket.ticket(),()->"alice",session));
        assertNull(gateway.consume(ticket.ticket(),()->"alice",session));
        ticket=gateway.issue("alice",session,resources);
        assertNull(gateway.consume(ticket.ticket(),()->"bob",session));
        var other=mock(HttpSession.class);when(other.getId()).thenReturn("other");
        ticket=gateway.issue("alice",session,resources);
        assertNull(gateway.consume(ticket.ticket(),()->"alice",other));
        when(catalog.device("alice","tb","d2")).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class,()->gateway.issue("alice",session,List.of(new SubscriptionGateway.Resource("tb","d2"))));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->gateway.issue("alice",session,Collections.nCopies(101,resources.getFirst())));
    }
    WebSocketSession connect() throws Exception {
        var ticket=gateway.issue("alice",session,List.of(new SubscriptionGateway.Resource("ta","d1")));
        var grant=gateway.consume(ticket.ticket(),()->"alice",session);
        var socket=mock(WebSocketSession.class); when(socket.getId()).thenReturn(UUID.randomUUID().toString());
        when(socket.isOpen()).thenReturn(true);when(socket.getAttributes()).thenReturn(Map.of("grant",grant));
        gateway.afterConnectionEstablished(socket);
        verify(socket,timeout(2000)).sendMessage(argThat(m->m.getPayload().toString().contains("ready")));
        return socket;
    }
    void publish(String tenant,String device) {
        var now=Instant.now();bus.publish(new EventMessage(UUID.randomUUID().toString(),"external/device/shadow",now,now,tenant,device,"v1","m1","APPLICATION",Map.of(),Map.of()));
    }
    @Test void onlyAuthorizedDeviceNotificationsCrossTheBoundaryAndRevocationClosesSocket() throws Exception {
        var socket=connect();
        publish("tb","d1");publish("ta","d2");
        publish("ta","d1");
        verify(socket,timeout(2000)).sendMessage(argThat(m->m.getPayload().toString().contains("invalidate")));
        verify(socket,times(2)).sendMessage(any());
        when(catalog.device("alice","ta","d1")).thenThrow(new AccessDeniedException("revoked"));
        publish("ta","d1");
        verify(socket,timeout(2000)).close(CloseStatus.POLICY_VIOLATION);
        verify(socket,times(2)).sendMessage(any());
    }
    @Test void logoutInvalidationIsCheckedBeforeSending() throws Exception {
        var socket=connect();when(session.getLastAccessedTime()).thenThrow(new IllegalStateException("invalidated"));
        publish("ta","d1");verify(socket,timeout(2000)).close(CloseStatus.POLICY_VIOLATION);
    }
    @Test void clientCannotPublishOrChangeArbitraryTopics() throws Exception {
        var socket=connect();gateway.handleTextMessage(socket,new TextMessage("subscribe device/**"));
        verify(socket,timeout(2000)).close(CloseStatus.BAD_DATA);
    }
}

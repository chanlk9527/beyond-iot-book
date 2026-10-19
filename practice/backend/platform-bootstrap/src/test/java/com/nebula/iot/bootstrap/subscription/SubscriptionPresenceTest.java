package com.nebula.iot.bootstrap.subscription;

import com.nebula.iot.cluster.JdbcNodeDirectory;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.device.api.CatalogViews.Device;
import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.gateway.JdbcDeviceSessionDirectory;
import com.nebula.iot.message.LocalEventBus;
import com.nebula.iot.network.mqtt.VertxMqttGateway;
import java.time.*;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionPresenceTest {
    private final DeviceKey key = new DeviceKey(new TenantId("tenant"),new DeviceId("device"));
    private final CatalogService catalog = mock(CatalogService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final DevicePresence presence = new DevicePresence(new LocalEventBus());
    private final SubscriptionController controller = new SubscriptionController(null,presence,catalog,
            beans.getBeanProvider(VertxMqttGateway.class));
    private JdbcTemplate jdbc;
    private JdbcNodeDirectory nodes;
    private JdbcDeviceSessionDirectory access,management;

    @BeforeEach void setup() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/cluster/V21__access_cluster.sql")).execute(data);
        jdbc = new JdbcTemplate(data);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
        nodes = new JdbcNodeDirectory(jdbc,tx,Duration.ofSeconds(15));
        nodes.register("access-a","Access",10);nodes.heartbeat("access-a",0,"null");
        access = new JdbcDeviceSessionDirectory(jdbc,tx,"access-a",Duration.ofSeconds(15));
        management = new JdbcDeviceSessionDirectory(jdbc,tx,"management-a",Duration.ofSeconds(15));
        when(catalog.device("alice","tenant","device")).thenReturn(
                new Device("device","tenant","p","Product","code","Device","v1","ACTIVE",1,1));
    }
    private String read() {
        var response = new MockHttpServletResponse();
        var result = controller.presence(()->"alice","device","tenant",response);
        assertEquals("no-store",response.getHeader("Cache-Control"));
        return result.state();
    }
    @Test void managementWithoutLocalMqttReadsRemoteSessionAndRelease() {
        presence.setDirectory(management);
        assertEquals("OFFLINE",read());
        var lease = access.acquire(key,"client");
        assertEquals("ONLINE",read());
        access.release(key,lease);
        assertEquals("OFFLINE",read());
    }
    @Test void expiredNodeOrSessionIsNotReportedAsOnline() {
        presence.setDirectory(management);
        var lease=access.acquire(key,"client");
        jdbc.update("UPDATE gateway_device_session SET lease_until=?",java.sql.Timestamp.from(Instant.EPOCH));
        assertEquals("OFFLINE",read());
        access.release(key,lease);
        access.acquire(key,"client");nodes.offline("access-a");
        assertEquals("OFFLINE",read());
    }
    @Test void localModeKeepsUnknownWithoutListenerAndOnlineWithListener() {
        assertEquals("UNKNOWN",read());
        var gateway=mock(VertxMqttGateway.class);
        when(gateway.isRunning()).thenReturn(true);beans.registerSingleton("gateway",gateway);
        assertEquals("OFFLINE",read());
        presence.changed(key,true);assertEquals("ONLINE",read());
        when(gateway.isRunning()).thenReturn(false);assertEquals("UNKNOWN",read());
    }
    @Test void authorizationIsCheckedBeforeSharedDirectoryAccess() {
        var directory=mock(JdbcDeviceSessionDirectory.class);presence.setDirectory(directory);
        when(catalog.device("alice","tenant","device")).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class,this::read);verifyNoInteractions(directory);
    }
}

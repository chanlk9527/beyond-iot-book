package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.core.auth.*;
import com.nebula.iot.core.device.*;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.model.*;
import com.nebula.iot.core.protocol.*;
import com.nebula.iot.protocol.official.*;
import com.nebula.iot.gateway.*;
import com.nebula.iot.message.EventBus;
import com.nebula.iot.device.application.DeviceCommandService;
import com.nebula.iot.core.message.DeviceMessageBody;
import com.nebula.iot.bootstrap.message.EventPublishingDeviceMessageHandler;
import com.nebula.iot.network.mqtt.*;
import io.vertx.core.Vertx;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 运行组装只配置接入组件，正式注册、认证和上行接收器可通过同类型Bean替换。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MqttAccessProperties.class)
@ConditionalOnProperty(prefix = "nebula.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttGatewayConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "nebula.mqtt", name = "development-mode", havingValue = "false", matchIfMissing = true)
    RegisteredMqttAccess registeredMqttAccess(ObjectProvider<com.nebula.iot.device.api.DeviceConnectionDirectory> directories) {
        return new RegisteredMqttAccess(directories);
    }

    @Bean
    DeviceConnectionRevocation deviceConnectionRevocation(VertxMqttGateway gateway) { return new DeviceConnectionRevocation(gateway); }

    @Bean(destroyMethod = "close")
    Vertx mqttVertx() { return Vertx.vertx(); }

    @Bean
    @ConditionalOnMissingBean(ProtocolSupport.class)
    ProtocolSupport mqttProtocol(ObjectProvider<DeviceAuthenticator> authenticators) {
        var authenticator = authenticators.getIfAvailable(() -> request -> CompletableFuture.completedFuture(new AuthenticationResult.Rejected("AUTHENTICATION_NOT_CONFIGURED")));
        return new OfficialMqttProtocol(authenticator);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nebula.mqtt", name = "development-mode", havingValue = "true")
    DevelopmentDeviceDirectory developmentDeviceDirectory(MqttAccessProperties properties) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "MQTT开发模式已启用：内存凭证与非持久化接收器仅用于本地联调");
        var registrations = new ArrayList<DevelopmentDeviceDirectory.Registration>();
        for (var entry : properties.devices()) {
            var tenant = new TenantId(entry.tenantId());
            var product = new ProductKey(tenant, new ProductId(entry.productId()));
            var key = new DeviceKey(tenant, new DeviceId(entry.deviceId()));
            var heatingLevel = new ValueSchema(ValueType.INTEGER, BigDecimal.ONE, BigDecimal.valueOf(100), null);
            var power = ValueSchema.of(ValueType.BOOLEAN);
            var model = new DeviceModel(product, "v1", Map.of(
                    "power", new PropertyDefinition("power", power, PropertyAccess.READ_WRITE),
                    "heatingLevel", new PropertyDefinition("heatingLevel", heatingLevel, PropertyAccess.READ_WRITE)), Map.of(), Map.of(
                    "setHeatingLevel", new OperationDefinition("setHeatingLevel", Map.of("heatingLevel", new FieldDefinition("heatingLevel", heatingLevel, true))),
                    "setPower", new OperationDefinition("setPower", Map.of("power", new FieldDefinition("power", power, true)))));
            var access = new DeviceAccessContext(new DeviceDescriptor(key, product, "v1", DeviceLifecycle.ACTIVE), model);
            if (entry.password() == null || entry.password().isBlank()) throw new IllegalArgumentException("开发密码必须显式配置");
            registrations.add(new DevelopmentDeviceDirectory.Registration(entry.clientId(), entry.username(), entry.password().getBytes(StandardCharsets.UTF_8), access));
        }
        return new DevelopmentDeviceDirectory(registrations);
    }

    @Bean
    @ConditionalOnMissingBean(DeviceMessageHandler.class)
    @ConditionalOnProperty(prefix = "nebula.mqtt", name = "development-mode", havingValue = "true")
    DevelopmentMessageInbox developmentMessageInbox() { return new DevelopmentMessageInbox(); }

    @Bean
    VertxMqttGateway mqttGateway(Vertx mqttVertx, MqttAccessProperties properties,
            ObjectProvider<DeviceAccessResolver> resolvers,
            ProtocolSupport protocol, ObjectProvider<DeviceMessageHandler> handlers, ObjectProvider<DeviceAccessGateway> gateways, EventBus eventBus, ObjectProvider<DeviceCommandService> commands) {
        var options = new MqttGatewayOptions(properties.host(), properties.port(), properties.maxConnections(),
                properties.maxMessageBytes(), properties.maxInFlight(), properties.workerThreads(), properties.maxQueuedWork(),
                properties.authenticationTimeout(), properties.processingTimeout(), properties.acknowledgementTimeout());
        // 尚未接入依赖时默认拒绝，不允许匿名设备或静默吞掉上行消息。
        var authenticator = protocol.authenticator();
        var resolver = resolvers.getIfAvailable(() -> key -> CompletableFuture.completedFuture(Optional.empty()));
        var delegate = handlers.getIfAvailable(() -> message -> {
            if (!properties.developmentMode() && (message.body() instanceof DeviceMessageBody.PropertyReport || message.body() instanceof DeviceMessageBody.CommandReply || message.body() instanceof DeviceMessageBody.PropertyWriteReply || message.body() instanceof DeviceMessageBody.RequestFailure))
                return CompletableFuture.completedFuture(null);
            return CompletableFuture.failedFuture(new IllegalStateException("未配置此类上行消息的业务接收器"));
        });
        var handler = new EventPublishingDeviceMessageHandler(message -> delegate.handle(message).thenRun(() -> commands.ifAvailable(service -> service.accept(message))), eventBus);
        var receiver = protocol instanceof OfficialMqttProtocol
                ? new OfficialSystemMessageHandler(message -> gateways.getObject().send(message), handler, java.time.Clock.systemUTC()) : handler;
        return new VertxMqttGateway(mqttVertx, options, authenticator, resolver, protocol.codec(Transport.MQTT), receiver);
    }

    @Bean
    SmartLifecycle mqttGatewayLifecycle(VertxMqttGateway gateway) {
        return new SmartLifecycle() {
            @Override public void start() { gateway.start().toCompletableFuture().orTimeout(15, TimeUnit.SECONDS).join(); }
            @Override public void stop() { gateway.stop().toCompletableFuture().orTimeout(15, TimeUnit.SECONDS).join(); }
            @Override public boolean isRunning() { return gateway.isRunning(); }
        };
    }

    @Bean
    HealthIndicator mqttHealthIndicator(VertxMqttGateway gateway) {
        return () -> (gateway.isRunning() ? Health.up() : Health.down())
                .withDetail("connections", gateway.connectionCount()).withDetail("sessions", gateway.sessionCount())
                .withDetail("accepted", gateway.acceptedMessageCount()).withDetail("rejected", gateway.rejectedMessageCount()).build();
    }
}

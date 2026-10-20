package com.nebula.iot.bootstrap.message;

import com.nebula.iot.message.EventBus;
import com.nebula.iot.message.LocalEventBus;
import com.nebula.iot.message.kafka.KafkaEventBus;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
public class MessageConfiguration {
    @Bean
    @ConditionalOnProperty(name="nebula.eventbus.mode", havingValue="local", matchIfMissing=true)
    EventBus localEventBus() { return new LocalEventBus(); }

    @Bean
    @ConditionalOnProperty(name="nebula.eventbus.mode", havingValue="kafka")
    EventBus eventBus(KafkaProperties kafka,
            @Value("${nebula.eventbus.mode:local}") String mode,
            @Value("${nebula.eventbus.topic:iot.platform.events.v1}") String topic,
            @Value("${nebula.eventbus.failure-topic:iot.platform.events.failed.v1}") String failureTopic,
            @Value("${nebula.eventbus.namespace:nebula}") String namespace,
            @Value("${nebula.eventbus.node-id:}") String nodeId,
            @Value("${nebula.eventbus.handler-timeout:30s}") Duration timeout) {
        if(nodeId.isBlank())nodeId=java.util.UUID.randomUUID().toString();
        return switch (mode) {
            case "local" -> new LocalEventBus();
            case "kafka" -> new KafkaEventBus(topic, failureTopic, namespace, nodeId,
                    kafka.buildProducerProperties(), kafka.buildConsumerProperties(), timeout).deferSubscriptionsUntilStart();
            default -> throw new IllegalArgumentException("Unknown EventBus mode: " + mode);
        };
    }
}

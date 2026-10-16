package com.nebula.iot.bootstrap.message;

import com.nebula.iot.message.EventBus;
import com.nebula.iot.message.LocalEventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MessageConfiguration {
    @Bean
    EventBus eventBus() { return new LocalEventBus(); }
}
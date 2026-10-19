package com.nebula.iot.device.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.application.DeviceShadowService;
import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.device.domain.DeviceShadowStore;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class DeviceShadowConfiguration {
    @Bean DeviceShadowService deviceShadowService(CatalogService catalog, CatalogMapper data, com.nebula.iot.device.application.ThingModelCache models,
            AccountDirectory accounts, TenantAccess tenants, ObjectMapper json, ObjectProvider<DeviceShadowStore> stores, org.springframework.context.ApplicationEventPublisher events) {
        return new DeviceShadowService(catalog, data, models, accounts, tenants, json, stores::getIfAvailable, events);
    }
    @Bean
    @ConditionalOnProperty(name = "nebula.redis.enabled", havingValue = "true")
    DeviceShadowStore deviceShadowStore(StringRedisTemplate redis, ObjectMapper json) {
        return new RedisDeviceShadowStore(redis, json);
    }
}


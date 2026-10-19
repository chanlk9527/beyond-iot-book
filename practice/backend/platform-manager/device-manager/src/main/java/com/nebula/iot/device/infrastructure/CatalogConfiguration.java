package com.nebula.iot.device.infrastructure;
import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@MapperScan(basePackageClasses=CatalogMapper.class)
public class CatalogConfiguration {
 @Bean com.nebula.iot.device.infrastructure.cache.ThreeLevelCache<String,com.nebula.iot.device.domain.CatalogRows.ConnectionRow> connectionCache(ObjectMapper json,ObjectProvider<StringRedisTemplate> redis,
   @Value("${nebula.cache.device.local-maximum-size:10000}") int maximumSize,
   @Value("${nebula.cache.device.local-ttl:PT5M}") Duration localTtl,
   @Value("${nebula.cache.device.redis-ttl:PT30M}") Duration redisTtl){
   return new com.nebula.iot.device.infrastructure.cache.ThreeLevelCache<>("nebula:cache:device-connection:v1",json,json.constructType(com.nebula.iot.device.domain.CatalogRows.ConnectionRow.class),redis.getIfAvailable(),maximumSize,localTtl,redisTtl,Object::toString);
 }
 @Bean com.nebula.iot.device.application.DeviceConnectionCache deviceConnectionCache(CatalogMapper data,com.nebula.iot.device.infrastructure.cache.ThreeLevelCache<String,com.nebula.iot.device.domain.CatalogRows.ConnectionRow> cache){return new com.nebula.iot.device.application.DeviceConnectionCache(data,cache);}
 @Bean com.nebula.iot.device.infrastructure.cache.ThreeLevelCache<com.nebula.iot.device.application.ThingModelCache.ModelKey,com.nebula.iot.device.api.CatalogViews.ModelVersion> thingModelCache(ObjectMapper json,ObjectProvider<StringRedisTemplate> redis,
   @Value("${nebula.cache.model.local-maximum-size:5000}") int maximumSize,
   @Value("${nebula.cache.model.local-ttl:PT10M}") Duration localTtl,
   @Value("${nebula.cache.model.redis-ttl:PT1H}") Duration redisTtl){
   return new com.nebula.iot.device.infrastructure.cache.ThreeLevelCache<>("nebula:cache:thing-model:v1",json,json.constructType(com.nebula.iot.device.api.CatalogViews.ModelVersion.class),redis.getIfAvailable(),maximumSize,localTtl,redisTtl,k -> k.productId()+":"+k.version());
 }
 @Bean com.nebula.iot.device.application.ThingModelCache thingModelDirectory(CatalogMapper data,ObjectMapper json,com.nebula.iot.device.infrastructure.cache.ThreeLevelCache<com.nebula.iot.device.application.ThingModelCache.ModelKey,com.nebula.iot.device.api.CatalogViews.ModelVersion> cache){return new com.nebula.iot.device.application.ThingModelCache(data,json,cache);}
 @Bean com.nebula.iot.device.api.DeviceConnectionDirectory registeredDeviceDirectory(CatalogMapper data,PasswordEncoder encoder,ObjectMapper json,com.nebula.iot.device.application.DeviceConnectionCache cache){return new com.nebula.iot.device.application.RegisteredDeviceDirectory(data,encoder,json,cache);}
 @Bean CatalogService catalogService(CatalogMapper data,AccountDirectory accounts,TenantAccess tenants,ObjectMapper json,PasswordEncoder encoder,org.springframework.context.ApplicationEventPublisher events,com.nebula.iot.device.application.ThingModelCache models){return new CatalogService(data,accounts,tenants,json,encoder,events,models);}
 @Bean com.nebula.iot.device.api.DeviceMediaAccess deviceMediaAccess(CatalogService catalog,AccountDirectory accounts){
  return (actor,tenant,id) -> {
   accounts.requirePermission(actor,"video:watch");
   var detail=catalog.deviceDetail(actor,tenant,id);
   if(!"ACTIVE".equals(detail.device().lifecycle()) || !detail.product().enabled())
    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Live device and product must be active");
   return new com.nebula.iot.device.api.DeviceMediaAccess.Target(detail.device().tenantId()==null?"platform":detail.device().tenantId(),detail.device().id());
  };
 }
}

package com.nebula.iot.device.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.application.*;
import com.nebula.iot.device.domain.FirmwareRepository;
import com.nebula.iot.device.domain.FirmwareInventoryRepository;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import com.nebula.iot.file.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@EnableConfigurationProperties(FirmwareSigningProperties.class)
public class FirmwareConfiguration {
    @Bean FirmwareInventoryRepository firmwareInventoryRepository(JdbcTemplate jdbc,ObjectMapper json) { return new JdbcFirmwareInventoryRepository(jdbc,json); }
    @Bean DeviceFirmwareService deviceFirmwareService(CatalogService catalog,CatalogMapper devices,FirmwareRepository firmware,FirmwareInventoryRepository inventory,ObjectMapper json,FirmwareSigningProperties properties) {
        return new DeviceFirmwareService(catalog,devices,firmware,inventory,json,java.time.Clock.systemUTC(),properties.getReportMaximumAge());
    }
    @Bean FirmwareRepository firmwareRepository(JdbcTemplate jdbc,ObjectMapper json) { return new JdbcFirmwareRepository(jdbc,json); }
    @Bean FirmwareService firmwareService(FirmwareRepository data,CatalogMapper products,CatalogService catalog,AccountDirectory accounts,TenantAccess tenants) {
        return new FirmwareService(data,products,catalog,accounts,tenants);
    }
    @Bean FirmwareFileService firmwareFileService(FirmwareService service,ObjectProvider<ObjectStorage> storage,FileStorageProperties files,FirmwareSigningProperties signing) {
        return new FirmwareFileService(service,storage,files.getMaximumUploadBytes(),files.getDownloadLifetime(),signing.getTrustedKeys());
    }
}

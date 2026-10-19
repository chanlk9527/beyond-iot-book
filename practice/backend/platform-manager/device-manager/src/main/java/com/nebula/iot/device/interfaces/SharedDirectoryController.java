package com.nebula.iot.device.interfaces;

import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.device.api.ProductDirectory;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@RequestMapping("/api/management/catalog/shared/categories")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class SharedDirectoryController {
 private final CatalogService service;
 public SharedDirectoryController(CatalogService service){this.service=service;}
 @GetMapping public ProductDirectory get(Principal actor){return service.directory(actor.getName(),null);}
 @PutMapping public ProductDirectory save(Principal actor,@RequestBody ProductDirectory input){return service.saveDirectory(actor.getName(),null,input);}
}
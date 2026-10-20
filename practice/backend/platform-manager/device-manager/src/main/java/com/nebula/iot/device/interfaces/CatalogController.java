package com.nebula.iot.device.interfaces;
import com.nebula.iot.device.application.CatalogService;
import com.nebula.iot.device.api.CatalogViews.*;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@RequestMapping({"/api/management/catalog/platform","/api/management/catalog/tenants/{tenantId}"})
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class CatalogController {
 private final CatalogService service;
 public CatalogController(CatalogService service){this.service=service;}
 public record CreateProduct(String code,String name,String description,com.nebula.iot.device.api.ProductConfiguration configuration){}
 public record Draft(String name,String description,ModelSpec model,Long revision,com.nebula.iot.device.api.ProductConfiguration configuration){}
 public record Publish(Long revision,boolean confirmEmpty){}
 public record Status(Boolean enabled,Long revision){}
 public record Registration(String productId,String code,String name,String modelVersion){}
 public record DeviceUpdate(String name,String lifecycle,Long revision){}
 public record Rotate(Long revision){}
 public record GatewayBinding(String gatewayId,Long revision){}
 @GetMapping("/devices/{id}/topology") public CatalogService.GatewayTopology topology(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return service.topology(actor.getName(),tenantId,id,page,size);}
 @PutMapping("/devices/{id}/gateway") public CatalogService.GatewayTopology bindGateway(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestBody GatewayBinding input){return service.bindGateway(actor.getName(),tenantId,id,input.gatewayId(),input.revision());}
 @DeleteMapping("/devices/{id}/gateway") public CatalogService.GatewayTopology unbindGateway(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestParam String gatewayId,@RequestParam Long revision){return service.unbindGateway(actor.getName(),tenantId,id,gatewayId,revision);}
 @GetMapping("/categories") public com.nebula.iot.device.api.ProductDirectory categories(Principal actor,@PathVariable(required=false)String tenantId){return service.directory(actor.getName(),tenantId);}
 @PutMapping("/categories") public com.nebula.iot.device.api.ProductDirectory categories(Principal actor,@PathVariable(required=false)String tenantId,@RequestBody com.nebula.iot.device.api.ProductDirectory input){return service.saveDirectory(actor.getName(),tenantId,input);}
 @GetMapping("/products") public Page<Product> products(Principal actor,@PathVariable(required=false)String tenantId,@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String category){return service.products(actor.getName(),tenantId,query,page,size,category);}
 @PostMapping("/products") @ResponseStatus(HttpStatus.CREATED) public Product create(Principal actor,@PathVariable(required=false)String tenantId,@RequestBody CreateProduct input){return service.createProduct(actor.getName(),tenantId,input.code(),input.name(),input.description(),input.configuration());}
 @GetMapping("/products/{id}") public Product product(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id){return service.product(actor.getName(),tenantId,id);}
 @PutMapping("/products/{id}/draft") public Product draft(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestBody Draft input){return service.saveDraft(actor.getName(),tenantId,id,input.name(),input.description(),input.model(),input.revision(),input.configuration());}
 @PostMapping("/products/{id}/publish") public Product publish(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestBody Publish input){return service.publish(actor.getName(),tenantId,id,input.revision(),input.confirmEmpty());}
 @PutMapping("/products/{id}/status") public Product status(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestBody Status input){return service.productStatus(actor.getName(),tenantId,id,input.enabled(),input.revision());}
 @DeleteMapping("/products/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteProduct(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestParam Long revision){service.deleteProduct(actor.getName(),tenantId,id,revision);}
 @GetMapping("/products/{id}/models") public List<ModelVersion> models(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id){return service.models(actor.getName(),tenantId,id);}
 @GetMapping("/devices") public Page<Device> devices(Principal actor,@PathVariable(required=false)String tenantId,@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="")String productId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return service.devices(actor.getName(),tenantId,query,productId,page,size);}
 @PostMapping("/devices") @ResponseStatus(HttpStatus.CREATED)
 public Credential register(Principal actor,@PathVariable(required=false)String tenantId,@RequestBody Registration input,jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");return service.register(actor.getName(),tenantId,input.productId(),input.code(),input.name(),input.modelVersion());}
 @GetMapping("/devices/{id}") public Device device(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id){return service.device(actor.getName(),tenantId,id);}
 @GetMapping("/devices/{id}/detail") public DeviceDetail detail(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id){return service.deviceDetail(actor.getName(),tenantId,id);}
 @GetMapping("/devices/{id}/runtime") public DeviceRuntimeSnapshot runtime(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id){return service.runtimeSnapshot(actor.getName(),tenantId,id);}
 @GetMapping("/devices/{id}/credential") public Credential credential(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");return service.credential(actor.getName(),tenantId,id);}
 @PutMapping("/devices/{id}") public Device update(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestBody DeviceUpdate input){return service.updateDevice(actor.getName(),tenantId,id,input.name(),input.lifecycle(),input.revision());}
 @DeleteMapping("/devices/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteDevice(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestParam Long revision){service.deleteDevice(actor.getName(),tenantId,id,revision);}
 @PostMapping("/devices/{id}/credential") public Credential rotate(Principal actor,@PathVariable(required=false)String tenantId,@PathVariable String id,@RequestBody Rotate input,jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");return service.rotate(actor.getName(),tenantId,id,input.revision());}
}

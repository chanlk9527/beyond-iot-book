package com.nebula.iot.device.application;

import com.nebula.iot.device.api.CatalogViews.*;
import com.nebula.iot.device.api.ProductConfiguration;
import com.nebula.iot.device.domain.CatalogRepository;
import com.nebula.iot.device.domain.CatalogRows.*;
import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.tenant.api.TenantAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.security.SecureRandom;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Transactional(readOnly=true)
public class CatalogService {
 private final CatalogRepository data;
 private final AccountDirectory accounts;
 private final TenantAccess tenants;
 private final ObjectMapper json;
 private final PasswordEncoder encoder;
 private final org.springframework.context.ApplicationEventPublisher events;
 private final SecureRandom random=new SecureRandom();
 private static final char[] DEVICE_ID_ALPHABET="abcdefghjkmnpqrstuvwxyz".toCharArray();
 private static final int DEVICE_ID_LENGTH=16;
 public CatalogService(CatalogRepository data,AccountDirectory accounts,TenantAccess tenants,ObjectMapper json,PasswordEncoder encoder,org.springframework.context.ApplicationEventPublisher events){this.data=data;this.accounts=accounts;this.tenants=tenants;this.json=json;this.encoder=encoder;this.events=events;}
 private String access(String actor,String tenant,String permission,boolean write){
  accounts.requirePermission(actor,permission);
  if(tenant==null){accounts.requirePlatformAdmin(actor);return "platform";}
  if(accounts.requireEnabled(actor).platformAdmin())return tenant;
  var member=tenants.requireMember(actor,tenant);
  if(write&&!Set.of("OWNER","ADMIN").contains(member.role()))throw new AccessDeniedException("Tenant member is read-only");
  return tenant;
 }
 // The historical platform row is now the single shared directory, independent of tenant ownership.
 private static final String SHARED_DIRECTORY_SCOPE="platform";
 public com.nebula.iot.device.api.ProductDirectory directory(String actor,String tenant){
  accounts.requirePermission(actor,"product:read");
  if(tenant!=null)access(actor,tenant,"product:read",false);
  return directoryFor(SHARED_DIRECTORY_SCOPE);
 }
 private com.nebula.iot.device.api.ProductDirectory directoryFor(String scope){
  var row=data.directory(scope);
  if(row==null)return new com.nebula.iot.device.api.ProductDirectory(0,List.of());
  try{return new com.nebula.iot.device.api.ProductDirectory(row.revision(),json.readValue(row.treeJson(),com.nebula.iot.device.api.ProductDirectory.class).items());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
 }
 @Transactional public com.nebula.iot.device.api.ProductDirectory saveDirectory(String actor,String tenant,com.nebula.iot.device.api.ProductDirectory input){
  accounts.requirePermission(actor,"product:write");accounts.requirePlatformAdmin(actor);
  String scope=SHARED_DIRECTORY_SCOPE;var current=directoryFor(scope);
  if(input.revision()!=current.revision())conflict("Directory changed; refresh and retry");
  for(String path:current.paths())if(!input.paths().contains(path)&&data.productCount(null,"",categoryFilter(path))>0)conflict("Category is used by products; move products before deleting it");
  try{String tree=json.writeValueAsString(input);if(tree.length()>32000)bad("Directory too large");
   if(input.revision()==0){try{data.insertDirectory(scope,tree);}catch(org.springframework.dao.DuplicateKeyException e){conflict("Directory changed; refresh and retry");}}else if(data.updateDirectory(scope,tree,input.revision())!=1)conflict("Directory changed; refresh and retry");
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException(e);}
  audit(actor,scope,"PRODUCT_DIRECTORY_UPDATED",scope);return directoryFor(scope);
 }
 private String readScope(String actor,String tenant,String permission){String scope=access(actor,tenant,permission,false);return tenant==null?null:scope;}
 public Page<Product> products(String actor,String tenant,String query,int page,int size,String category){
  String scope=readScope(actor,tenant,"product:read");paging(query,page,size);
  return new Page<>(data.products(scope,query,categoryFilter(category),size,((long)page-1)*size).stream().map(this::productView).toList(),data.productCount(scope,query,categoryFilter(category)),page,size);
 }
 public Product product(String actor,String tenant,String id){return productView(productRow(readScope(actor,tenant,"product:read"),id));}
 @Transactional public Product createProduct(String actor,String tenant,String code,String name,String description,ProductConfiguration configuration){
  String scope=access(actor,tenant,"product:write",true);validCode(code);validName(name);validDescription(description);
  String id=UUID.randomUUID().toString();
  try{data.insertProduct(id,tenant,scope,code,name.strip(),description,encode(ModelSpec.empty()),encodeConfiguration(configuration==null?ProductConfiguration.defaults():configuration));}
  catch(org.springframework.dao.DuplicateKeyException duplicate){conflict("Product code already exists in this scope");}
  audit(actor,scope,"PRODUCT_CREATED",id);return productView(productRow(scope,id));
 }
 @Transactional public Product saveDraft(String actor,String tenant,String id,String name,String description,ModelSpec model,Long revision,ProductConfiguration configuration){
  String scope=access(actor,tenant,"product:write",true);validName(name);validDescription(description);validRevision(revision);
  var current=productRow(scope,id);String encoded=encode(model);String settings=encodeConfiguration(configuration==null?decodeConfiguration(current.configuration()):configuration);
  if(data.updateProduct(scope,id,name.strip(),description,encoded,settings,revision)!=1)conflict("Product changed; refresh and retry");
  audit(actor,scope,"PRODUCT_DRAFT_SAVED",id);return productView(productRow(scope,id));
 }
 @Transactional public Product publish(String actor,String tenant,String id,Long revision,boolean confirmEmpty){
  String scope=access(actor,tenant,"product:publish",true);validRevision(revision);
  var row=lock(scope,id);
  if(!row.enabled())conflict("Disabled product cannot be published");
  if(row.revision()!=revision)conflict("Product changed; refresh and retry");
  var draft=decode(row.draftModel());
  if(draft.equals(ModelSpec.empty())&&!confirmEmpty)conflict("空物模型未定义任何能力，请明确确认后发布");
  var latest=row.latestVersion()==null?null:data.model(id,row.latestVersion());
  if(latest!=null&&draft.equals(decode(latest.modelJson())))conflict("草稿与最新发布版本一致，无需重复发布");
  var maximum=data.models(id).stream().map(ModelRow::version).filter(v->v.matches("v[1-9][0-9]*"))
    .map(v->new java.math.BigInteger(v.substring(1))).max(java.math.BigInteger::compareTo).orElse(java.math.BigInteger.ZERO);
  String version="v"+maximum.add(java.math.BigInteger.ONE);
  if(version.length()>32)conflict("模型版本号已达到上限");
  data.insertModel(id,version,encode(decode(row.draftModel())));
  if(data.publish(scope,id,version,revision)!=1)conflict("Product changed");
  audit(actor,scope,"MODEL_PUBLISHED",id);return productView(productRow(scope,id));
 }
 @Transactional public Product productStatus(String actor,String tenant,String id,Boolean enabled,Long revision){
  String scope=access(actor,tenant,"product:publish",true);validRevision(revision);if(enabled==null)bad("Status required");lock(scope,id);
  if(data.productStatus(scope,id,enabled,revision)!=1)conflict("Product changed; refresh and retry");
  events.publishEvent(new com.nebula.iot.device.api.DeviceConnectionDirectory.CacheInvalidated(null,true));
  audit(actor,scope,"PRODUCT_STATUS_CHANGED",id);return productView(productRow(scope,id));
 }
 @Transactional public void deleteProduct(String actor,String tenant,String id,Long revision){
  String scope=access(actor,tenant,"product:write",true);validRevision(revision);
  var row=lock(scope,id);
  if(row.revision()!=revision)conflict("Product changed; refresh and retry");
  if(row.enabled())conflict("Disable the product before deleting it");
  if(data.deviceCount(scope,"",id)>0)conflict("Product has devices and cannot be deleted");
  data.deleteModels(id);
  if(data.deleteProduct(scope,id,revision)!=1)conflict("Product changed; refresh and retry");
  audit(actor,scope,"PRODUCT_DELETED",id);
 }
 public List<ModelVersion> models(String actor,String tenant,String id){productRow(readScope(actor,tenant,"product:read"),id);return data.models(id).stream().map(m->new ModelVersion(m.version(),decode(m.modelJson()),m.publishedAt(),m.deviceCount())).toList();}
 public Page<Device> devices(String actor,String tenant,String query,String productId,int page,int size){
  String scope=readScope(actor,tenant,"device:read");paging(query,page,size);if(productId==null||productId.length()>36)bad("Invalid product filter");
  if(!productId.isEmpty())productRow(scope,productId);
  return new Page<>(data.devices(scope,query,productId,size,((long)page-1)*size).stream().map(this::deviceView).toList(),data.deviceCount(scope,query,productId),page,size);
 }
 public Device device(String actor,String tenant,String id){return deviceView(deviceRow(readScope(actor,tenant,"device:read"),id));}
 public DeviceDetail deviceDetail(String actor,String tenant,String id){
  String scope=readScope(actor,tenant,"device:read");
  var row=deviceRow(scope,id); var product=productRow(scope,row.productId()); var model=data.model(row.productId(),row.modelVersion());
  if(model==null) missing();
  return new DeviceDetail(deviceView(row),productView(product),new ModelVersion(model.version(),decode(model.modelJson()),model.publishedAt(),model.deviceCount()));
 }
 public DeviceRuntimeSnapshot runtimeSnapshot(String actor,String tenant,String id){
  String scope=readScope(actor,tenant,"device:read"); var row=deviceRow(scope,id); var model=data.model(row.productId(),row.modelVersion());
  if(model==null) missing();
  var spec=decode(model.modelJson());
  List<PropertySnapshot> properties=new ArrayList<>();
  spec.properties().values().forEach(p->properties.add(new PropertySnapshot(p.id(),p.schema().type().name(),p.access().name(),"暂无上报","",false)));
  List<EventSnapshot> events=new ArrayList<>();
  spec.events().keySet().forEach(e->events.add(new EventSnapshot(e,"暂无事件","","暂无设备事件上报")));
  return new DeviceRuntimeSnapshot(properties,events);
 }
 @Transactional public Credential register(String actor,String tenant,String productId,String code,String name,String version){
  String scope=access(actor,tenant,"device:write",true);String actualCode=code==null||code.isBlank()?deviceCode():code.strip();validCode(actualCode);validName(name);
  // Registering a device exposes a new credential, so both capabilities are required.
  accounts.requirePermission(actor,"device:credential");
  var product=lock(scope,productId);
  if(!product.enabled())conflict("Product disabled");
  if(version==null||data.model(productId,version)==null)bad("A published model version is required");
  String id=deviceIdentifier();String secret=secret();
  try{data.insertDevice(id,tenant,scope,productId,actualCode,name.strip(),version,encoder.encode(secret),secret);}
  catch(org.springframework.dao.DuplicateKeyException duplicate){conflict("Device code already exists in this scope");}
  invalidateConnection(id);audit(actor,scope,"DEVICE_REGISTERED",id);return credential(deviceRow(scope,id),secret);
 }
 public Credential credential(String actor,String tenant,String id){
  String scope=access(actor,tenant,"device:credential",false);var row=deviceRow(scope,id);
  if(row.credentialSecret()==null)conflict("Credential is unavailable; rotate it first");
  return credential(row,row.credentialSecret());
 }
 @Transactional public Device updateDevice(String actor,String tenant,String id,String name,String lifecycle,Long revision){
  String scope=access(actor,tenant,"device:write",true);validName(name);validRevision(revision);
  if(lifecycle==null||!Set.of("ACTIVE","DISABLED","RETIRED").contains(lifecycle))bad("Invalid lifecycle");
  var row=deviceRow(scope,id);var product=lock(scope,row.productId());
  if(row.lifecycle().equals("RETIRED")&&!lifecycle.equals("RETIRED"))conflict("Retired device cannot be reactivated");
  if(lifecycle.equals("ACTIVE")&&!product.enabled())conflict("Product disabled");
  if(data.updateDevice(scope,id,name.strip(),lifecycle,revision)!=1)conflict("Device changed; refresh and retry");
  if(!lifecycle.equals("ACTIVE"))revoke(scope,id);
  invalidateConnection(id);audit(actor,scope,"DEVICE_UPDATED",id);return deviceView(deviceRow(scope,id));
 }
 @Transactional public void deleteDevice(String actor,String tenant,String id,Long revision){
  String scope=access(actor,tenant,"device:write",true);validRevision(revision);var row=deviceRow(scope,id);
  if(!row.lifecycle().equals("DISABLED")) conflict("Only disabled devices can be deleted");
  if(data.deleteDevice(scope,id,revision)!=1) conflict("Device changed; refresh and retry");
  invalidateConnection(id);audit(actor,scope,"DEVICE_DELETED",id);
 }
 @Transactional public Credential rotate(String actor,String tenant,String id,Long revision){
  String scope=access(actor,tenant,"device:credential",true);validRevision(revision);var row=deviceRow(scope,id);lock(scope,row.productId());
  if(row.lifecycle().equals("RETIRED"))conflict("Retired device cannot rotate credentials");
  String secret=secret();
  if(data.rotate(scope,id,encoder.encode(secret),secret,revision)!=1)conflict("Device changed; refresh and retry");
  revoke(scope,id);
  invalidateConnection(id);audit(actor,scope,"DEVICE_CREDENTIAL_ROTATED",id);return credential(deviceRow(scope,id),secret);
 }
 private void revoke(String scope,String id){events.publishEvent(new com.nebula.iot.device.api.DeviceConnectionDirectory.Revoked(new com.nebula.iot.core.device.DeviceKey(new com.nebula.iot.core.identity.TenantId(scope),new com.nebula.iot.core.identity.DeviceId(id))));}
 private void invalidateConnection(String id){events.publishEvent(new com.nebula.iot.device.api.DeviceConnectionDirectory.CacheInvalidated(id,false));}
 private Credential credential(DeviceRow row,String secret){return new Credential(deviceView(row),row.id(),row.id(),secret);}
 private String deviceIdentifier(){char[] value=new char[DEVICE_ID_LENGTH];for(int i=0;i<value.length;i++)value[i]=DEVICE_ID_ALPHABET[random.nextInt(DEVICE_ID_ALPHABET.length)];return new String(value);}
 private String deviceCode(){return "device-"+deviceIdentifier().substring(0,8);}
 private String secret(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 private ProductRow productRow(String scope,String id){var row=data.product(scope,id);if(row==null)missing();return row;}
 private ProductRow lock(String scope,String id){var row=data.lockProduct(scope,id);if(row==null)missing();return row;}
 private DeviceRow deviceRow(String scope,String id){var row=data.device(scope,id);if(row==null)missing();return row;}
 private Product productView(ProductRow p){return new Product(p.id(),p.tenantId(),p.code(),p.name(),p.description(),p.enabled(),p.latestVersion(),p.revision(),decode(p.draftModel()),decodeConfiguration(p.configuration()));}
 private String encodeConfiguration(ProductConfiguration value){if(!value.category().isEmpty()&&!value.category().matches("[a-z]{3,4}-[a-z]{3,4}"))bad("Product category must be a second-level category ID");try{String encoded=json.writeValueAsString(value);if(encoded.length()>8192)bad("Product configuration too large");return encoded;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException(e);}}
 private ProductConfiguration decodeConfiguration(String value){try{return json.readValue(value,ProductConfiguration.class);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Stored product configuration is invalid",e);}}
 private Device deviceView(DeviceRow d){return new Device(d.id(),d.tenantId(),d.productId(),d.productName(),d.code(),d.name(),d.modelVersion(),d.lifecycle(),d.credentialVersion(),d.revision());}
 private String encode(ModelSpec model){if(model==null)bad("Model required");try{String value=json.writeValueAsString(model);if(value.length()>65536)bad("Model too large");return value;}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid model");}}
 private ModelSpec decode(String value){try{return json.readValue(value,ModelSpec.class);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Stored model is invalid",invalid);}}
 private void audit(String actor,String scope,String action,String resource){data.audit(UUID.randomUUID().toString(),scope,accounts.requireEnabled(actor).id(),action,resource);}
 private String categoryFilter(String category){
  if(category==null||category.length()>100)bad("Invalid product category");
  if(category.isEmpty())return "";
  try{
   String encoded=json.writeValueAsString(category);
   // Configuration is always serialized by this service. Match the full JSON field,
   // or the parent path followed by the separator, never an arbitrary text value.
   return "\"category\":"+(category.contains("-")?encoded:encoded.substring(0,encoded.length()-1)+"-");
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException(e);}
 }
 private static void paging(String query,int page,int size){if(query==null||query.length()>100||page<1||size<1||size>100)bad("Invalid pagination or search");}
 private static void validCode(String code){if(code==null||!code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))bad("Code must contain 1 to 64 letters, digits, dots, underscores or hyphens");}
 private static void validName(String name){if(name==null||name.isBlank()||name.length()>100)bad("Name must contain 1 to 100 characters");}
 private static void validDescription(String d){if(d==null||d.length()>1000)bad("Description must not exceed 1000 characters");}
 private static void validRevision(Long r){if(r==null||r<0)bad("Revision required");}
 private static void missing(){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource unavailable in this scope");}
 private static void bad(String m){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
 private static void conflict(String m){throw new ResponseStatusException(HttpStatus.CONFLICT,m);}
}

package com.nebula.iot.device.infrastructure;
import com.nebula.iot.device.domain.CatalogRepository;
import com.nebula.iot.device.domain.CatalogRows.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface CatalogMapper extends CatalogRepository {
 @Select("SELECT COUNT(*) FROM device_production_batch WHERE product_id=#{productId}") long productionBatches(String productId);
 @Select("SELECT COUNT(*) FROM device_production_item WHERE device_id=#{deviceId}") long productionItems(String deviceId);
 @Select("SELECT d.id,d.scope_key AS scopeKey,d.product_id AS productId,d.model_version AS modelVersion,d.credential_hash AS credentialHash,m.model_json AS modelJson FROM device_registration d JOIN device_product p ON p.id=d.product_id AND p.scope_key=d.scope_key JOIN device_model_version m ON m.product_id=d.product_id AND m.version=d.model_version LEFT JOIN tenant t ON t.id=d.tenant_id WHERE d.id=#{id} AND d.lifecycle='ACTIVE' AND p.enabled=TRUE AND (d.tenant_id IS NULL OR t.enabled=TRUE)")
 ConnectionRow connection(String id);

 @Select("SELECT tree_json AS treeJson,revision FROM device_product_directory WHERE scope_key=#{scope}") DirectoryRow directory(String scope);
 @Insert("INSERT INTO device_product_directory(scope_key,tree_json,revision) VALUES(#{scope},#{tree},1)") int insertDirectory(@Param("scope")String scope,@Param("tree")String tree);
 @Update("UPDATE device_product_directory SET tree_json=#{tree},revision=revision+1 WHERE scope_key=#{scope} AND revision=#{revision}") int updateDirectory(@Param("scope")String scope,@Param("tree")String tree,@Param("revision")long revision);
 String P="id,tenant_id AS tenantId,scope_key AS scopeKey,code,name,description,enabled,draft_model AS draftModel,latest_version AS latestVersion,revision,configuration";
 String D="d.id,d.tenant_id AS tenantId,d.scope_key AS scopeKey,d.product_id AS productId,p.name AS productName,d.code,d.name,d.model_version AS modelVersion,d.lifecycle,d.credential_version AS credentialVersion,d.revision,d.credential_secret AS credentialSecret";
 String SEARCH="(COALESCE(#{scope}, '') = '' OR scope_key=#{scope}) AND (POSITION(LOWER(#{query}) IN LOWER(name))>0 OR POSITION(LOWER(#{query}) IN LOWER(code))>0) AND (#{category}='' OR POSITION(#{category} IN configuration)>0)";
 @Select("SELECT "+P+" FROM device_product WHERE "+SEARCH+" ORDER BY code,id LIMIT #{limit} OFFSET #{offset}")
 List<ProductRow> products(@Param("scope")String scope,@Param("query")String query,@Param("category")String category,@Param("limit")int limit,@Param("offset")long offset);
 @Select("SELECT COUNT(*) FROM device_product WHERE "+SEARCH) long productCount(@Param("scope")String scope,@Param("query")String query,@Param("category")String category);
 @Select("SELECT "+P+" FROM device_product WHERE (COALESCE(#{scope}, '') = '' OR scope_key=#{scope}) AND id=#{id}") ProductRow product(@Param("scope")String scope,@Param("id")String id);
 @Select("SELECT "+P+" FROM device_product WHERE scope_key=#{scope} AND id=#{id} FOR UPDATE") ProductRow lockProduct(@Param("scope")String scope,@Param("id")String id);
 @Insert("INSERT INTO device_product(id,tenant_id,scope_key,code,name,description,draft_model,configuration) VALUES(#{id},#{tenant},#{scope},#{code},#{name},#{description},#{model},#{configuration})")
 void insertProduct(@Param("id")String id,@Param("tenant")String tenant,@Param("scope")String scope,@Param("code")String code,@Param("name")String name,@Param("description")String description,@Param("model")String model,@Param("configuration")String configuration);
 @Update("UPDATE device_product SET name=#{name},description=#{description},draft_model=#{model},configuration=#{configuration},revision=revision+1 WHERE scope_key=#{scope} AND id=#{id} AND revision=#{revision}")
 int updateProduct(@Param("scope")String scope,@Param("id")String id,@Param("name")String name,@Param("description")String description,@Param("model")String model,@Param("configuration")String configuration,@Param("revision")long revision);
 @Update("UPDATE device_product SET enabled=#{enabled},revision=revision+1 WHERE scope_key=#{scope} AND id=#{id} AND revision=#{revision}")
 int productStatus(@Param("scope")String scope,@Param("id")String id,@Param("enabled")boolean enabled,@Param("revision")long revision);
 @Delete("DELETE FROM device_model_version WHERE product_id=#{id}") void deleteModels(@Param("id")String id);
 @Delete("DELETE FROM device_product WHERE scope_key=#{scope} AND id=#{id} AND revision=#{revision} AND enabled=FALSE") int deleteProduct(@Param("scope")String scope,@Param("id")String id,@Param("revision")long revision);
 @Update("UPDATE device_product SET latest_version=#{version},revision=revision+1 WHERE scope_key=#{scope} AND id=#{id} AND revision=#{revision}")
 int publish(@Param("scope")String scope,@Param("id")String id,@Param("version")String version,@Param("revision")long revision);
 @Insert("INSERT INTO device_model_version(product_id,version,model_json) VALUES(#{id},#{version},#{model})")
 void insertModel(@Param("id")String id,@Param("version")String version,@Param("model")String model);
 String M="m.version,m.model_json AS modelJson,m.published_at AS publishedAt,(SELECT COUNT(*) FROM device_registration d WHERE d.product_id=m.product_id AND d.model_version=m.version) AS deviceCount";
 @Select("SELECT "+M+" FROM device_model_version m WHERE m.product_id=#{id} ORDER BY m.published_at DESC,m.version") List<ModelRow> models(String id);
 @Select("SELECT "+M+" FROM device_model_version m WHERE m.product_id=#{id} AND m.version=#{version}") ModelRow model(@Param("id")String id,@Param("version")String version);
 String DS="(COALESCE(#{scope}, '') = '' OR d.scope_key=#{scope}) AND (POSITION(LOWER(#{query}) IN LOWER(d.name))>0 OR POSITION(LOWER(#{query}) IN LOWER(d.code))>0) AND (#{productId}='' OR d.product_id=#{productId})";
 @Select("SELECT "+D+" FROM device_registration d JOIN device_product p ON p.id=d.product_id WHERE "+DS+" ORDER BY d.code,d.id LIMIT #{limit} OFFSET #{offset}")
 List<DeviceRow> devices(@Param("scope")String scope,@Param("query")String query,@Param("productId")String productId,@Param("limit")int limit,@Param("offset")long offset);
 @Select("SELECT COUNT(*) FROM device_registration d WHERE "+DS) long deviceCount(@Param("scope")String scope,@Param("query")String query,@Param("productId")String productId);
 @Select("SELECT "+D+" FROM device_registration d JOIN device_product p ON p.id=d.product_id WHERE (COALESCE(#{scope}, '') = '' OR d.scope_key=#{scope}) AND d.id=#{id}") DeviceRow device(@Param("scope")String scope,@Param("id")String id);
 @Insert("INSERT INTO device_registration(id,tenant_id,scope_key,product_id,code,name,model_version,lifecycle,credential_hash,credential_secret) VALUES(#{id},#{tenant},#{scope},#{productId},#{code},#{name},#{version},'ACTIVE',#{hash},#{secret})")
 void insertDevice(@Param("id")String id,@Param("tenant")String tenant,@Param("scope")String scope,@Param("productId")String productId,@Param("code")String code,@Param("name")String name,@Param("version")String version,@Param("hash")String hash,@Param("secret")String secret);
 @Update("UPDATE device_registration SET name=#{name},lifecycle=#{lifecycle},revision=revision+1 WHERE scope_key=#{scope} AND id=#{id} AND revision=#{revision}")
 int updateDevice(@Param("scope")String scope,@Param("id")String id,@Param("name")String name,@Param("lifecycle")String lifecycle,@Param("revision")long revision);
 @Delete("DELETE FROM device_registration WHERE scope_key=#{scope} AND id=#{id} AND revision=#{revision} AND lifecycle='DISABLED'")
 int deleteDevice(@Param("scope")String scope,@Param("id")String id,@Param("revision")long revision);
 @Update("UPDATE device_registration SET credential_hash=#{hash},credential_secret=#{secret},credential_version=credential_version+1,revision=revision+1 WHERE scope_key=#{scope} AND id=#{id} AND revision=#{revision} AND lifecycle<>'RETIRED'")
 int rotate(@Param("scope")String scope,@Param("id")String id,@Param("hash")String hash,@Param("secret")String secret,@Param("revision")long revision);
 @Insert("INSERT INTO device_management_audit(id,scope_key,actor_id,action,resource_id) VALUES(#{id},#{scope},#{actor},#{action},#{resource})")
 void audit(@Param("id")String id,@Param("scope")String scope,@Param("actor")String actor,@Param("action")String action,@Param("resource")String resource);
}

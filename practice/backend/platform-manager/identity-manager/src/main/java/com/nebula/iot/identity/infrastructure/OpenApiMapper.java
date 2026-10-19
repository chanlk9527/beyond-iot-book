package com.nebula.iot.identity.infrastructure;

import com.nebula.iot.identity.domain.OpenApiRepository;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface OpenApiMapper extends OpenApiRepository {
    String ELIGIBLE = " AND NOT EXISTS (SELECT 1 FROM openapi_app_profile p JOIN tenant tenant_owner ON tenant_owner.id=p.tenant_id WHERE p.app_id=a.app_id AND (p.review_status<>'APPROVED' OR tenant_owner.enabled=FALSE))";
    @Override @Insert("INSERT INTO openapi_application(id,app_id,name,secret_hash,scopes) VALUES(#{id},#{appId},#{name},#{secretHash},#{scopes})")
    void insertApplication(@Param("id") String id,@Param("appId") String appId,@Param("name") String name,@Param("secretHash") String secretHash,@Param("scopes") String scopes);
    @Override @Select("SELECT a.app_id AS appId,a.name,a.secret_hash AS secretHash,a.scopes,a.enabled,a.revision FROM openapi_application a WHERE a.app_id=#{appId}"+ELIGIBLE+" FOR UPDATE")
    Optional<ApplicationRow> application(String appId);
    @Override @Insert("INSERT INTO openapi_access_token(token_hash,app_id,subject,tenant_id,scopes,expires_at,app_revision) VALUES(#{hash},#{appId},#{subject},#{tenantId},#{scopes},#{expiresAt},#{appRevision})")
    void insertToken(@Param("hash") String hash,@Param("appId") String appId,@Param("subject") String subject,@Param("tenantId") String tenantId,@Param("scopes") String scopes,@Param("expiresAt") Instant expiresAt,@Param("appRevision") long appRevision);
    @Override @Select("SELECT t.app_id AS appId,t.subject,t.tenant_id AS tenantId,t.scopes,t.expires_at AS expiresAt,t.revoked,t.app_revision AS appRevision FROM openapi_access_token t JOIN openapi_application a ON a.app_id=t.app_id WHERE token_hash=#{hash} AND a.enabled=TRUE AND a.revision=t.app_revision"+ELIGIBLE)
    Optional<TokenRow> token(String hash);
    @Override @Update("UPDATE openapi_access_token SET revoked=TRUE WHERE token_hash=#{hash} AND revoked=FALSE")
    int revokeToken(@Param("hash") String hash);
    @Override @Update("UPDATE openapi_application SET enabled=#{enabled},revision=revision+1 WHERE app_id=#{appId} AND revision=#{revision}")
    int setEnabled(@Param("appId") String appId,@Param("enabled") boolean enabled,@Param("revision") long revision);
    @Override @Update("UPDATE openapi_application SET secret_hash=#{secretHash},revision=revision+1 WHERE app_id=#{appId} AND revision=#{revision}")
    int rotateSecret(@Param("appId") String appId,@Param("secretHash") String secretHash,@Param("revision") long revision);
    @Override @Select("SELECT tenant_id FROM openapi_app_profile WHERE app_id=#{appId}") String tenantId(String appId);
}

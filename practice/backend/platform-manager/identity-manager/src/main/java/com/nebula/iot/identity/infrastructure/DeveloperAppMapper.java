package com.nebula.iot.identity.infrastructure;

import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.*;

/** Safe application projections; credentials are never returned to the console listing. */
public interface DeveloperAppMapper {
    record App(String appId,String name,String tenantId,String tenantName,String requestedScopes,String scopes,
               String reviewStatus,String reviewNote,boolean enabled,long revision) {}
    record Audit(String actor,String action,String detail,Instant occurredAt) {}
    String SELECT = "SELECT a.app_id AS appId,a.name,p.tenant_id AS tenantId,t.name AS tenantName,p.requested_scopes AS requestedScopes,a.scopes,p.review_status AS reviewStatus,p.review_note AS reviewNote,a.enabled,a.revision FROM openapi_application a JOIN openapi_app_profile p ON a.app_id=p.app_id JOIN tenant t ON t.id=p.tenant_id ";
    @Select(SELECT+"WHERE p.tenant_id=#{tenantId} ORDER BY a.created_at DESC,a.app_id LIMIT 100")
    List<App> tenantApps(String tenantId);
    @Select(SELECT+"ORDER BY a.created_at DESC,a.app_id LIMIT 100") List<App> allApps();
    @Select(SELECT+"WHERE a.app_id=#{appId}") App app(String appId);
    @Select("SELECT app_id FROM openapi_application WHERE app_id=#{appId} FOR UPDATE") String lock(String appId);
    @Insert("INSERT INTO openapi_app_profile(app_id,tenant_id,requested_scopes,review_status) VALUES(#{appId},#{tenantId},#{scopes},'PENDING')")
    void profile(@Param("appId") String appId,@Param("tenantId") String tenantId,@Param("scopes") String scopes);
    @Update("UPDATE openapi_application SET enabled=FALSE,scopes='',revision=revision+1 WHERE app_id=#{appId}") void pending(String appId);
    @Update("UPDATE openapi_app_profile SET requested_scopes=#{scopes},review_status='PENDING',review_note='' WHERE app_id=#{appId}")
    void submit(@Param("appId") String appId,@Param("scopes") String scopes);
    @Update("UPDATE openapi_app_profile SET review_status=#{status},review_note=#{note} WHERE app_id=#{appId}")
    void review(@Param("appId") String appId,@Param("status") String status,@Param("note") String note);
    @Update("UPDATE openapi_application SET enabled=#{enabled},scopes=#{scopes},revision=revision+1 WHERE app_id=#{appId}")
    void approve(@Param("appId") String appId,@Param("enabled") boolean enabled,@Param("scopes") String scopes);
    @Insert("INSERT INTO openapi_app_audit(id,app_id,actor,action,detail) VALUES(#{id},#{appId},#{actor},#{action},#{detail})")
    void audit(@Param("id") String id,@Param("appId") String appId,@Param("actor") String actor,@Param("action") String action,@Param("detail") String detail);
    @Select("SELECT actor,action,detail,occurred_at AS occurredAt FROM openapi_app_audit WHERE app_id=#{appId} ORDER BY occurred_at DESC,id DESC LIMIT 100") List<Audit> auditRecords(String appId);
}

package com.nebula.iot.device.domain;

public final class CatalogRows {
 private CatalogRows() {}
 public record ConnectionRow(String id,String scopeKey,String productId,String modelVersion,String credentialHash,String modelJson) {
  @Override public String toString(){return "ConnectionRow[redacted]";}
 }
 public record DirectoryRow(String treeJson,long revision) {}
 public record ProductRow(String id,String tenantId,String scopeKey,String code,String name,String description,boolean enabled,String draftModel,String latestVersion,long revision,String configuration) {}
 public record ModelRow(String version,String modelJson,java.time.OffsetDateTime publishedAt,long deviceCount) {}
 public record DeviceRow(String id,String tenantId,String scopeKey,String productId,String productName,String code,String name,String modelVersion,String lifecycle,long credentialVersion,long revision,String credentialSecret) {}
}

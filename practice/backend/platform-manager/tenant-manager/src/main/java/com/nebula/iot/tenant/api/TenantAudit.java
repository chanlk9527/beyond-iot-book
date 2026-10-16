package com.nebula.iot.tenant.api;

public record TenantAudit(String id, String actorId, String action, String subjectId, java.time.OffsetDateTime occurredAt) {}

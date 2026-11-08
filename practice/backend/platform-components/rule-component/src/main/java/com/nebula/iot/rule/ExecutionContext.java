package com.nebula.iot.rule;

/** Identity must be supplied by an authorized caller, never by untrusted event payload. */
public record ExecutionContext(String tenantId, String runId, String eventId, String ruleId, long ruleVersion, String nodeId) {
    public ExecutionContext {
        JsonValues.identifier(tenantId);
        JsonValues.identifier(runId);
        JsonValues.identifier(eventId);
        JsonValues.identifier(ruleId);
        JsonValues.identifier(nodeId);
        if (ruleVersion < 1) throw new IllegalArgumentException("Version must be positive");
    }
}

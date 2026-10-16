ALTER TABLE tenant_workspace ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE tenant_membership ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE tenant_audit (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenant_workspace(id),
    actor_id VARCHAR(36) NOT NULL,
    action VARCHAR(50) NOT NULL,
    subject_id VARCHAR(36),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX tenant_audit_scope_idx ON tenant_audit(tenant_id, occurred_at);

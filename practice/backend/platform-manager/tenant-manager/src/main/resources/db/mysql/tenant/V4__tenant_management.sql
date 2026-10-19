ALTER TABLE tenant_workspace ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE tenant_membership ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE tenant_audit (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    FOREIGN KEY (tenant_id) REFERENCES tenant_workspace(id),
    actor_id VARCHAR(36) NOT NULL,
    action VARCHAR(50) NOT NULL,
    subject_id VARCHAR(36),
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX tenant_audit_scope_idx ON tenant_audit(tenant_id, occurred_at);

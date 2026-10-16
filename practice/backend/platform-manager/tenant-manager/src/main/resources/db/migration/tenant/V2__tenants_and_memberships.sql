CREATE TABLE tenant_workspace (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Account identifiers are external references owned by identity-manager.
CREATE TABLE tenant_membership (
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenant_workspace(id),
    account_id VARCHAR(36) NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, account_id)
);
CREATE INDEX tenant_membership_account_idx ON tenant_membership(account_id);

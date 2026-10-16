-- Refuse ambiguous existing memberships rather than selecting or dropping a tenant.
ALTER TABLE tenant_membership ADD CONSTRAINT tenant_membership_single_account UNIQUE (account_id);
ALTER TABLE identity_account ADD COLUMN tenant_id VARCHAR(36) REFERENCES tenant(id);
UPDATE identity_account SET tenant_id = (SELECT m.tenant_id FROM tenant_membership m WHERE m.account_id = identity_account.id);
ALTER TABLE identity_account ADD CONSTRAINT account_requires_tenant CHECK (platform_admin = TRUE OR tenant_id IS NOT NULL);
ALTER TABLE identity_account ADD CONSTRAINT account_tenant_identity UNIQUE (id, tenant_id);
ALTER TABLE tenant_membership ADD CONSTRAINT membership_matches_account_tenant FOREIGN KEY (account_id, tenant_id) REFERENCES identity_account(id, tenant_id);
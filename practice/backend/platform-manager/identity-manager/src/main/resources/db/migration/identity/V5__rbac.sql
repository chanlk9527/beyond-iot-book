ALTER TABLE identity_account ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE identity_role (
 id VARCHAR(36) PRIMARY KEY,
 code VARCHAR(64) NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE identity_role_permission (
 role_id VARCHAR(36) NOT NULL REFERENCES identity_role(id),
 permission VARCHAR(100) NOT NULL,
 PRIMARY KEY(role_id,permission)
);
CREATE TABLE identity_account_role (
 account_id VARCHAR(36) NOT NULL REFERENCES identity_account(id),
 role_id VARCHAR(36) NOT NULL REFERENCES identity_role(id),
 PRIMARY KEY(account_id,role_id)
);
CREATE TABLE identity_security_lock (id INTEGER PRIMARY KEY);
INSERT INTO identity_security_lock(id) VALUES(1);
CREATE TABLE identity_audit (
 id VARCHAR(36) PRIMARY KEY,
 actor_id VARCHAR(36) NOT NULL,
 action VARCHAR(50) NOT NULL,
 subject_id VARCHAR(36) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

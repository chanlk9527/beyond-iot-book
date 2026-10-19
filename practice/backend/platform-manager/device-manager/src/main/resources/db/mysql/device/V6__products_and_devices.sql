CREATE TABLE device_product (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id VARCHAR(36),
 scope_key VARCHAR(36) NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 draft_model LONGTEXT NOT NULL,
 latest_version VARCHAR(32),
 revision BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(scope_key,code), UNIQUE(id,scope_key),
 CHECK ((tenant_id IS NULL AND scope_key='platform') OR (tenant_id IS NOT NULL AND scope_key=tenant_id))
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE device_model_version (
 product_id VARCHAR(36) NOT NULL,
 FOREIGN KEY (product_id) REFERENCES device_product(id),
 version VARCHAR(32) NOT NULL,
 model_json LONGTEXT NOT NULL,
 published_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(product_id,version)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE device_registration (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id VARCHAR(36),
 scope_key VARCHAR(36) NOT NULL,
 product_id VARCHAR(36) NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 model_version VARCHAR(32) NOT NULL,
 lifecycle VARCHAR(20) NOT NULL DEFAULT 'REGISTERED',
 credential_hash VARCHAR(255) NOT NULL,
 credential_version BIGINT NOT NULL DEFAULT 1,
 revision BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(scope_key,code),
 FOREIGN KEY(product_id,scope_key) REFERENCES device_product(id,scope_key),
 FOREIGN KEY(product_id,model_version) REFERENCES device_model_version(product_id,version),
 CHECK ((tenant_id IS NULL AND scope_key='platform') OR (tenant_id IS NOT NULL AND scope_key=tenant_id)),
 CHECK (lifecycle IN ('REGISTERED','ACTIVE','DISABLED','RETIRED'))
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX device_registration_product_idx ON device_registration(product_id);
CREATE TABLE device_management_audit (
 id VARCHAR(36) PRIMARY KEY,
 scope_key VARCHAR(36) NOT NULL,
 actor_id VARCHAR(36) NOT NULL,
 action VARCHAR(40) NOT NULL,
 resource_id VARCHAR(36) NOT NULL,
 occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

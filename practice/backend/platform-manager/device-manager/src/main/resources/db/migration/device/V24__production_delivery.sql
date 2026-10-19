CREATE TABLE device_production_batch (
 id VARCHAR(36) PRIMARY KEY,
 scope_key VARCHAR(36) NOT NULL,
 tenant_id VARCHAR(36),
 code VARCHAR(40) NOT NULL,
 name VARCHAR(100) NOT NULL,
 product_id VARCHAR(36) NOT NULL,
 model_version VARCHAR(32) NOT NULL,
 quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 50),
 hardware_version VARCHAR(100) NOT NULL,
 firmware_version VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNED','PRODUCING','RELEASED','DELIVERED')),
 revision BIGINT NOT NULL DEFAULT 0,
 recipient VARCHAR(200) NOT NULL DEFAULT '',
 delivery_ref VARCHAR(100) NOT NULL DEFAULT '',
 created_at BIGINT NOT NULL,
 delivered_at BIGINT,
 UNIQUE(scope_key,code),
 FOREIGN KEY(product_id,scope_key) REFERENCES device_product(id,scope_key),
 FOREIGN KEY(product_id,model_version) REFERENCES device_model_version(product_id,version),
 CHECK ((tenant_id IS NULL AND scope_key='platform') OR (tenant_id IS NOT NULL AND scope_key=tenant_id))
);
CREATE INDEX device_production_batch_scope ON device_production_batch(scope_key,created_at);
CREATE TABLE device_production_item (
 batch_id VARCHAR(36) NOT NULL REFERENCES device_production_batch(id),
 device_id VARCHAR(36) NOT NULL REFERENCES device_registration(id),
 sequence_number INTEGER NOT NULL,
 burned_version BIGINT,
 tested_version BIGINT,
 test_result VARCHAR(10) CHECK (test_result IN ('PASS','FAIL')),
 PRIMARY KEY(batch_id,device_id),
 UNIQUE(device_id), UNIQUE(batch_id,sequence_number)
);
CREATE TABLE device_production_event (
 id VARCHAR(36) PRIMARY KEY,
 batch_id VARCHAR(36) NOT NULL REFERENCES device_production_batch(id),
 device_id VARCHAR(36),
 actor VARCHAR(100) NOT NULL,
 action VARCHAR(40) NOT NULL,
 station VARCHAR(100) NOT NULL,
 result VARCHAR(20) NOT NULL,
 note VARCHAR(1000) NOT NULL,
 credential_version BIGINT,
 occurred_at BIGINT NOT NULL
);
CREATE INDEX device_production_event_batch ON device_production_event(batch_id,occurred_at);

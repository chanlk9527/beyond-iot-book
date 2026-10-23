CREATE TABLE device_firmware_channel (
 product_id VARCHAR(36) NOT NULL,
 scope_key VARCHAR(36) NOT NULL,
 channel_id INTEGER NOT NULL CHECK (channel_id IN (0,1,3,9) OR channel_id BETWEEN 10 AND 255),
 component VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 version_rule VARCHAR(20) NOT NULL CHECK (version_rule IN ('TUYA','OPAQUE')),
 created_at BIGINT NOT NULL,
 PRIMARY KEY(product_id,channel_id),
 UNIQUE(product_id,component),
 FOREIGN KEY(product_id,scope_key) REFERENCES device_product(id,scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE device_firmware_version (
 id VARCHAR(36) PRIMARY KEY,
 product_id VARCHAR(36) NOT NULL,
 channel_id INTEGER NOT NULL,
 version VARCHAR(100) NOT NULL,
 description VARCHAR(1000) NOT NULL,
 source_versions TEXT NOT NULL,
 hardware_versions TEXT NOT NULL,
 artifact TEXT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 revision BIGINT NOT NULL DEFAULT 0,
 created_at BIGINT NOT NULL,
 UNIQUE(product_id,channel_id,version),
 FOREIGN KEY(product_id,channel_id) REFERENCES device_firmware_channel(product_id,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX device_firmware_version_listing ON device_firmware_version(product_id,channel_id,created_at);

CREATE TABLE device_firmware_file (
 id VARCHAR(36) PRIMARY KEY,
 product_id VARCHAR(36) NOT NULL,
 channel_id INTEGER NOT NULL,
 storage_reference TEXT NOT NULL,
 size_bytes BIGINT NOT NULL CHECK (size_bytes>0),
 sha256 VARCHAR(64) NOT NULL,
 key_id VARCHAR(100) NOT NULL,
 signature VARCHAR(100) NOT NULL,
 created_at BIGINT NOT NULL,
 FOREIGN KEY(product_id,channel_id) REFERENCES device_firmware_channel(product_id,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX device_firmware_file_product ON device_firmware_file(product_id,channel_id,created_at);

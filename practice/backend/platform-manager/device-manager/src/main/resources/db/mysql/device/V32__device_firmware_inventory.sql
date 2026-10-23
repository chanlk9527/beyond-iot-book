CREATE TABLE device_firmware_inventory (
 device_id VARCHAR(36) PRIMARY KEY,
 FOREIGN KEY(device_id) REFERENCES device_registration(id) ON DELETE CASCADE,
 scope_key VARCHAR(36) NOT NULL,
 document_json TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE device_firmware_report (
 device_id VARCHAR(36) NOT NULL,
 FOREIGN KEY(device_id) REFERENCES device_registration(id) ON DELETE CASCADE,
 message_id VARCHAR(128) NOT NULL,
 report_kind VARCHAR(16) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 received_at BIGINT NOT NULL,
 PRIMARY KEY(device_id,message_id,report_kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX device_firmware_report_retention ON device_firmware_report(received_at);

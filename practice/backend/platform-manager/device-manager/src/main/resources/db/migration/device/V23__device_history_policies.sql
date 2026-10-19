CREATE TABLE device_history_policy (
 product_id VARCHAR(36) PRIMARY KEY REFERENCES device_product(id) ON DELETE CASCADE,
 policy_json TEXT NOT NULL,
 revision BIGINT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE TABLE device_history_inbox (
 id VARCHAR(64) PRIMARY KEY,
 fingerprint VARCHAR(64) NOT NULL,
 records_json TEXT NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT FALSE,
 received_at BIGINT NOT NULL
);
CREATE INDEX device_history_inbox_cleanup ON device_history_inbox(completed,received_at);
CREATE TABLE device_history_series (
 id VARCHAR(64) PRIMARY KEY,
 product_id VARCHAR(36) NOT NULL,
 kind VARCHAR(16) NOT NULL,
 identifier VARCHAR(64) NOT NULL,
 series_json TEXT NOT NULL,
 retention_days INTEGER NOT NULL
);
CREATE INDEX device_history_series_product ON device_history_series(product_id);

CREATE TABLE device_product_directory (
 scope_key VARCHAR(64) PRIMARY KEY,
 tree_json VARCHAR(32000) NOT NULL,
 revision BIGINT NOT NULL
);

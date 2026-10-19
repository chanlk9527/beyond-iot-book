CREATE TABLE device_product_directory (
 scope_key VARCHAR(64) PRIMARY KEY,
 tree_json LONGTEXT NOT NULL,
 revision BIGINT NOT NULL
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

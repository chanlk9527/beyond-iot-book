ALTER TABLE device_registration ADD CONSTRAINT device_registration_id_scope_unique UNIQUE(id,scope_key);
CREATE TABLE device_gateway_child (
 child_id VARCHAR(36) PRIMARY KEY,
 gateway_id VARCHAR(36) NOT NULL,
 scope_key VARCHAR(36) NOT NULL,
 FOREIGN KEY(child_id,scope_key) REFERENCES device_registration(id,scope_key),
 FOREIGN KEY(gateway_id,scope_key) REFERENCES device_registration(id,scope_key),
 CHECK(child_id<>gateway_id)
);
CREATE INDEX device_gateway_child_parent_idx ON device_gateway_child(gateway_id,scope_key);
CREATE TABLE cluster_control_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO cluster_control_lock(lock_id) VALUES (1);

CREATE TABLE cluster_access_node (
    node_id VARCHAR(64) PRIMARY KEY,
    node_name VARCHAR(128) NOT NULL,
    node_state VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    heartbeat_at TIMESTAMP(3) NOT NULL,
    lease_until TIMESTAMP(3) NOT NULL,
    connections INTEGER NOT NULL,
    max_connections INTEGER NOT NULL,
    metrics_json TEXT NOT NULL,
    revision BIGINT NOT NULL
);
CREATE INDEX cluster_node_lease ON cluster_access_node(lease_until);

CREATE TABLE gateway_device_session (
    tenant_id VARCHAR(128) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    node_id VARCHAR(64),
    session_id VARCHAR(64),
    client_id VARCHAR(256) UNIQUE,
    epoch BIGINT NOT NULL,
    lease_until TIMESTAMP(3) NOT NULL,
    PRIMARY KEY(tenant_id,device_id)
);
CREATE INDEX gateway_session_node ON gateway_device_session(node_id,lease_until);

CREATE TABLE cluster_node_operation (
    operation_id VARCHAR(64) PRIMARY KEY,
    node_id VARCHAR(64) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    operation_state VARCHAR(32) NOT NULL,
    operation_type VARCHAR(16) NOT NULL DEFAULT 'DRAIN',
    batch_size INTEGER NOT NULL,
    deadline TIMESTAMP(3) NOT NULL,
    remaining INTEGER NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    failure VARCHAR(255)
);
CREATE INDEX cluster_operation_node ON cluster_node_operation(node_id,operation_state);

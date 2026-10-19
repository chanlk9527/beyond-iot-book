CREATE TABLE device_command (
    message_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    command_id VARCHAR(128),
    command_kind VARCHAR(32) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    request_json TEXT NOT NULL,
    command_state VARCHAR(32) NOT NULL,
    result_code VARCHAR(64),
    result_message VARCHAR(512),
    created_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    attempt INTEGER NOT NULL,
    target_node VARCHAR(64),
    target_session VARCHAR(64),
    target_epoch BIGINT,
    signal_at TIMESTAMP(3)
);
CREATE INDEX device_command_expiry ON device_command(command_state,expires_at);
CREATE INDEX device_command_device ON device_command(tenant_id,device_id,created_at);
CREATE TABLE device_command_outbox (
    event_id VARCHAR(64) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    payload VARCHAR(32) NOT NULL,
    published BOOLEAN NOT NULL,
    claim_until TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL
);
CREATE INDEX command_outbox_pending ON device_command_outbox(published,created_at);
CREATE TABLE device_command_transition (
    transition_id VARCHAR(64) PRIMARY KEY,
    message_id VARCHAR(128) NOT NULL,
    from_state VARCHAR(32),
    to_state VARCHAR(32) NOT NULL,
    result_code VARCHAR(64),
    detail VARCHAR(512),
    occurred_at TIMESTAMP(3) NOT NULL
);
CREATE INDEX command_transition_message ON device_command_transition(message_id,occurred_at);

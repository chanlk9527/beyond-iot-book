CREATE TABLE event_route_namespace (
    namespace_id VARCHAR(100) PRIMARY KEY,
    revision BIGINT NOT NULL
);
CREATE TABLE event_route_node (
    namespace_id VARCHAR(100) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    endpoint VARCHAR(300) NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    applied_revision BIGINT NOT NULL,
    PRIMARY KEY(namespace_id,node_id)
);
CREATE TABLE event_route_subscription (
    namespace_id VARCHAR(100) NOT NULL,
    route_id VARCHAR(64) NOT NULL,
    subscription_name VARCHAR(100) NOT NULL,
    subscription_mode VARCHAR(20) NOT NULL,
    target_node VARCHAR(100) NOT NULL,
    spec_json TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    PRIMARY KEY(namespace_id,route_id)
);
CREATE TABLE event_route_member (
    namespace_id VARCHAR(100) NOT NULL,
    route_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    PRIMARY KEY(namespace_id,route_id,node_id)
);
-- One durable record spans outbound delivery and the receiver's inbox.
CREATE TABLE event_route_delivery (
    namespace_id VARCHAR(100) NOT NULL,
    delivery_id VARCHAR(64) NOT NULL,
    route_id VARCHAR(64) NOT NULL,
    event_json TEXT NOT NULL,
    source_node VARCHAR(100) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    delivery_state VARCHAR(20) NOT NULL,
    owner_id VARCHAR(100),
    lease_until TIMESTAMP NOT NULL,
    available_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    attempts INTEGER NOT NULL,
    PRIMARY KEY(namespace_id,delivery_id)
);
CREATE INDEX event_delivery_pending ON event_route_delivery(namespace_id,delivery_state,available_at,lease_until);
CREATE INDEX event_delivery_route ON event_route_delivery(namespace_id,route_id,delivery_state);

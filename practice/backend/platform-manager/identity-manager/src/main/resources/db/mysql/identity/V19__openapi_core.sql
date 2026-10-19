CREATE TABLE openapi_application (
    id VARCHAR(36) PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    secret_hash VARCHAR(255) NOT NULL,
    scopes VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE openapi_access_token (
    token_hash VARCHAR(128) PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    subject VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(64),
    scopes VARCHAR(1000) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    app_revision BIGINT NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_openapi_token_app FOREIGN KEY (app_id) REFERENCES openapi_application(app_id)
);

CREATE INDEX idx_openapi_token_expiry ON openapi_access_token(expires_at);

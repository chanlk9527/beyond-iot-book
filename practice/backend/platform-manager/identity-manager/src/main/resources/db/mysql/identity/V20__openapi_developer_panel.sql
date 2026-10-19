CREATE TABLE openapi_app_profile (
    app_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    requested_scopes VARCHAR(1000) NOT NULL,
    review_status VARCHAR(20) NOT NULL,
    review_note VARCHAR(1000) NOT NULL DEFAULT '',
    FOREIGN KEY (app_id) REFERENCES openapi_application(app_id),
    FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);
CREATE TABLE openapi_app_audit (
    id VARCHAR(36) PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    action VARCHAR(32) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_openapi_app_tenant ON openapi_app_profile(tenant_id);
CREATE INDEX idx_openapi_app_audit ON openapi_app_audit(app_id,occurred_at);

CREATE TABLE IF NOT EXISTS connector_profile (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    auth_mode VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_json VARCHAR(8192) NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS connector_profile (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    auth_mode VARCHAR(32) NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    config_json TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

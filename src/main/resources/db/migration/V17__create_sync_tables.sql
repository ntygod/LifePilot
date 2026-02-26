-- V17__create_sync_tables.sql
-- 外部数据源同步模块数据库 Schema

-- 1. 同步配置表
CREATE TABLE IF NOT EXISTS sync_profiles (
    id                      TEXT PRIMARY KEY,
    name                    TEXT NOT NULL,
    connector_type          TEXT NOT NULL,
    connection_params_json  TEXT NOT NULL,
    sync_direction          TEXT NOT NULL DEFAULT 'BIDIRECTIONAL',
    conflict_policy         TEXT NOT NULL DEFAULT 'LAST_WRITE_WINS',
    cron_expression         TEXT NOT NULL DEFAULT '0 */15 * * * *',
    enabled                 INTEGER NOT NULL DEFAULT 1,
    data_type_filter_json   TEXT,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

-- 2. 同步映射表
CREATE TABLE IF NOT EXISTS sync_records (
    id                  TEXT PRIMARY KEY,
    profile_id          TEXT NOT NULL REFERENCES sync_profiles(id) ON DELETE CASCADE,
    local_entity_type   TEXT NOT NULL,
    local_entity_id     TEXT NOT NULL,
    remote_entity_id    TEXT NOT NULL,
    etag                TEXT,
    remote_updated_at   TEXT,
    last_sync_at        TEXT NOT NULL,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);
CREATE INDEX idx_sync_records_profile ON sync_records(profile_id);
CREATE UNIQUE INDEX idx_sync_records_mapping ON sync_records(profile_id, local_entity_type, local_entity_id);

-- 3. 凭证表
CREATE TABLE IF NOT EXISTS sync_credentials (
    id              TEXT PRIMARY KEY,
    profile_id      TEXT NOT NULL REFERENCES sync_profiles(id) ON DELETE CASCADE,
    credential_type TEXT NOT NULL,
    encrypted_value TEXT NOT NULL,
    iv              TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
CREATE INDEX idx_sync_credentials_profile ON sync_credentials(profile_id);

-- 4. 冲突表
CREATE TABLE IF NOT EXISTS sync_conflicts (
    id                      TEXT PRIMARY KEY,
    profile_id              TEXT NOT NULL REFERENCES sync_profiles(id) ON DELETE CASCADE,
    local_entity_type       TEXT NOT NULL,
    local_entity_id         TEXT NOT NULL,
    local_snapshot_json     TEXT NOT NULL,
    remote_snapshot_json    TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'UNRESOLVED',
    resolved_at             TEXT,
    created_at              TEXT NOT NULL
);
CREATE INDEX idx_sync_conflicts_profile ON sync_conflicts(profile_id);
CREATE INDEX idx_sync_conflicts_status ON sync_conflicts(status);

-- 5. 同步状态表
CREATE TABLE IF NOT EXISTS sync_state (
    id                  TEXT PRIMARY KEY,
    profile_id          TEXT NOT NULL REFERENCES sync_profiles(id) ON DELETE CASCADE,
    sync_token          TEXT,
    last_sync_at        TEXT,
    last_sync_status    TEXT,
    last_error_message  TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_sync_state_profile ON sync_state(profile_id);

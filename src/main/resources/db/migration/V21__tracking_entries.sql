-- ============================================================
-- V20: 统一追踪注册中心（快递/航班/行程）
-- ============================================================

CREATE TABLE tracking_entries (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    type TEXT NOT NULL,
    tracking_key TEXT NOT NULL,
    title TEXT NOT NULL,
    status TEXT,
    last_checked_at TEXT,
    relevant_at TEXT,
    metadata TEXT,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_tracking_entries_user_active
    ON tracking_entries(user_id, active, created_at DESC);

CREATE INDEX idx_tracking_entries_type_active
    ON tracking_entries(type, active, created_at DESC);

CREATE UNIQUE INDEX idx_tracking_entries_user_type_key
    ON tracking_entries(user_id, type, tracking_key);

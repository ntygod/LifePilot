-- V22: 创建 Web UI 会话管理表
-- 用于前端多会话管理功能

CREATE TABLE IF NOT EXISTS chat_sessions (
    id              TEXT PRIMARY KEY,
    title           TEXT NOT NULL DEFAULT '新对话',
    summary         TEXT,
    message_count   INTEGER NOT NULL DEFAULT 0,
    is_pinned       INTEGER NOT NULL DEFAULT 0,
    last_message_at TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_last_message ON chat_sessions(last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_pinned ON chat_sessions(is_pinned DESC, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated ON chat_sessions(updated_at DESC);

-- V52: 创建记忆注入记录表
-- 记录每次 AI 回复时注入了哪些记忆实体，用于反馈闭环回溯

CREATE TABLE IF NOT EXISTS memory_injection_records (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    entity_ids_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_injection_records_message
    ON memory_injection_records(message_id);

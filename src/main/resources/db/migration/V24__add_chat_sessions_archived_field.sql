-- V24: 添加 chat_sessions 表的 archived 字段
-- 用于支持会话归档功能

ALTER TABLE chat_sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_chat_sessions_archived 
    ON chat_sessions(archived, last_message_at DESC);

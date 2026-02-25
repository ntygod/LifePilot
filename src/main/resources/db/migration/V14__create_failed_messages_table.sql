-- 重建失败消息表，替换 V12 中的旧版 schema
-- V12 的 failed_messages 表使用 channel_type/content_json 等列名，
-- 通道适配器实际使用 channel/content 等列名，此处统一为新 schema

DROP TABLE IF EXISTS failed_messages;
DROP TRIGGER IF EXISTS trg_failed_messages_updated_at;

CREATE TABLE failed_messages (
    id          TEXT PRIMARY KEY,
    channel     TEXT NOT NULL,
    user_id     TEXT NOT NULL,
    response_id TEXT NOT NULL,
    content     TEXT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error       TEXT,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_failed_messages_channel ON failed_messages(channel);
CREATE INDEX idx_failed_messages_created_at ON failed_messages(created_at);

CREATE TRIGGER trg_failed_messages_updated_at
    AFTER UPDATE ON failed_messages
    FOR EACH ROW
BEGIN
    UPDATE failed_messages
    SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE id = NEW.id;
END;

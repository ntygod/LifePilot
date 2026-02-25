-- 失败消息持久化表，记录超过重试上限的通道发送失败消息
CREATE TABLE IF NOT EXISTS failed_messages (
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

-- 通知历史记录表
CREATE TABLE notification_history (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    type_id         TEXT,
    urgency         TEXT NOT NULL,           -- HIGH / MEDIUM / LOW
    content_json    TEXT NOT NULL,            -- ResponseContent 序列化 JSON
    channel         TEXT NOT NULL,            -- WEB / WECOM / FEISHU / DINGTALK / passive
    read_status     TEXT NOT NULL DEFAULT 'UNREAD',  -- UNREAD / READ
    status          TEXT NOT NULL DEFAULT 'SENT',    -- SENT / FAILED
    metadata_json   TEXT,
    sent_at         TEXT NOT NULL,            -- ISO 8601
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_notification_history_user_id ON notification_history(user_id);
CREATE INDEX idx_notification_history_sent_at ON notification_history(sent_at);
CREATE INDEX idx_notification_history_read_status ON notification_history(user_id, read_status);

-- ============================================================
-- V2: 简化通知模型，移除等级/设置/被动队列
-- ============================================================

DROP INDEX IF EXISTS idx_notification_history_read_status;
DROP INDEX IF EXISTS idx_notification_history_sent_at;
DROP INDEX IF EXISTS idx_notification_history_user_id;

CREATE TABLE notification_history_new (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    type_id         TEXT,
    content_json    TEXT NOT NULL,
    channel         TEXT NOT NULL,
    read_status     TEXT NOT NULL DEFAULT 'UNREAD',
    status          TEXT NOT NULL DEFAULT 'SENT',
    metadata_json   TEXT,
    sent_at         TEXT NOT NULL,
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

INSERT INTO notification_history_new (
    id, user_id, type_id, content_json, channel,
    read_status, status, metadata_json, sent_at, created_at, updated_at
)
SELECT
    id, user_id, type_id, content_json, channel,
    read_status, status, metadata_json, sent_at, created_at, updated_at
FROM notification_history;

DROP TABLE notification_history;
ALTER TABLE notification_history_new RENAME TO notification_history;

CREATE INDEX idx_notification_history_read_status ON notification_history(user_id, read_status);
CREATE INDEX idx_notification_history_sent_at ON notification_history(sent_at);
CREATE INDEX idx_notification_history_user_id ON notification_history(user_id);

DROP TABLE IF EXISTS notification_settings;
DROP TABLE IF EXISTS passive_notification_queue;

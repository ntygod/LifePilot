-- 通知设置表
CREATE TABLE notification_settings (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    type_id         TEXT NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    channels_json   TEXT NOT NULL DEFAULT '["WEB","WECOM","FEISHU","DINGTALK"]',
    min_urgency     TEXT NOT NULL DEFAULT 'LOW',
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(user_id, type_id)
);

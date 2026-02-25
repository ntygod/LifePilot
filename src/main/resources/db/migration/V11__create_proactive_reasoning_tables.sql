-- 频率状态表
CREATE TABLE IF NOT EXISTS frequency_states (
    notification_type       TEXT PRIMARY KEY,
    state                   TEXT NOT NULL DEFAULT 'NORMAL',
    consecutive_ignore_count INTEGER NOT NULL DEFAULT 0,
    last_notified_at        TEXT,
    created_at              TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at              TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 主动通知记录表
CREATE TABLE IF NOT EXISTS proactive_notifications (
    id                  TEXT PRIMARY KEY,
    notification_type   TEXT NOT NULL,
    urgency             TEXT NOT NULL,
    content             TEXT NOT NULL,
    channel             TEXT NOT NULL,
    response_status     TEXT NOT NULL DEFAULT 'PENDING',
    sent_at             TEXT NOT NULL,
    responded_at        TEXT,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_proactive_notifications_type ON proactive_notifications(notification_type);
CREATE INDEX idx_proactive_notifications_sent ON proactive_notifications(sent_at);

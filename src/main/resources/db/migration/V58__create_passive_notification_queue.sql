-- 被动通知队列表
CREATE TABLE passive_notification_queue (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    type_id         TEXT,
    urgency         TEXT NOT NULL,
    content_json    TEXT NOT NULL,
    delivered       INTEGER NOT NULL DEFAULT 0,  -- 0=未投递, 1=已投递
    enqueued_at     TEXT NOT NULL,                -- ISO 8601
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_passive_queue_delivered ON passive_notification_queue(delivered);
CREATE INDEX idx_passive_queue_user_id ON passive_notification_queue(user_id, delivered);

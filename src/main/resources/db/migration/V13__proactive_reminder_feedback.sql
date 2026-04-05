-- ============================================================
-- V8: 主动提醒反馈与主题静默
-- ============================================================

CREATE TABLE proactive_reminder_feedback (
    id TEXT PRIMARY KEY,
    notification_id TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    topic_key TEXT NOT NULL,
    feedback_type TEXT NOT NULL,
    comment TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (notification_id) REFERENCES notification_history(id) ON DELETE CASCADE
);

CREATE TABLE proactive_reminder_topic_preferences (
    user_id TEXT NOT NULL,
    topic_key TEXT NOT NULL,
    muted INTEGER NOT NULL DEFAULT 0,
    muted_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (user_id, topic_key)
);

CREATE INDEX idx_proactive_reminder_feedback_user_topic
    ON proactive_reminder_feedback(user_id, topic_key, updated_at DESC);

CREATE INDEX idx_proactive_reminder_feedback_type
    ON proactive_reminder_feedback(feedback_type, updated_at DESC);

CREATE INDEX idx_proactive_reminder_topic_preferences_muted
    ON proactive_reminder_topic_preferences(user_id, muted, updated_at DESC);

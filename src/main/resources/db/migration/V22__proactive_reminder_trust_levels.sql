-- 主动提醒信任梯度表
CREATE TABLE IF NOT EXISTS proactive_reminder_trust_levels (
    user_id         TEXT NOT NULL,
    candidate_type  TEXT NOT NULL,
    trust_level     TEXT NOT NULL DEFAULT 'OBSERVE',
    consecutive_positive INTEGER NOT NULL DEFAULT 0,
    consecutive_negative INTEGER NOT NULL DEFAULT 0,
    cooldown_until  TEXT,
    last_feedback_type TEXT,
    updated_at      TEXT NOT NULL,
    PRIMARY KEY (user_id, candidate_type)
);

CREATE INDEX IF NOT EXISTS idx_proactive_reminder_trust_user_type
    ON proactive_reminder_trust_levels(user_id, candidate_type);

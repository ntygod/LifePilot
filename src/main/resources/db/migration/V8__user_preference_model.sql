-- 五维偏好模型表 — 从行为反馈中自动学习用户偏好

CREATE TABLE IF NOT EXISTS proactive_user_preferences (
    user_id              TEXT    NOT NULL,
    dimension            TEXT    NOT NULL,
    preference_key       TEXT    NOT NULL,
    preference_value     REAL    NOT NULL DEFAULT 0.5,
    observation_count    INTEGER NOT NULL DEFAULT 0,
    last_observed_at     TEXT,
    updated_at           TEXT    NOT NULL,
    PRIMARY KEY (user_id, dimension, preference_key)
);

CREATE INDEX IF NOT EXISTS idx_proactive_user_preferences_user
    ON proactive_user_preferences (user_id, dimension, updated_at DESC);

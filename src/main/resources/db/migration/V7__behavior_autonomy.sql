-- 行为自主度配置表 — 每种行为独立的自主度级别 + 信任追踪

CREATE TABLE IF NOT EXISTS proactive_behavior_autonomy (
    user_id               TEXT    NOT NULL,
    behavior_name         TEXT    NOT NULL,
    autonomy_level        TEXT    NOT NULL DEFAULT 'A',
    consecutive_positive  INTEGER NOT NULL DEFAULT 0,
    consecutive_negative  INTEGER NOT NULL DEFAULT 0,
    upgrade_suggested     INTEGER NOT NULL DEFAULT 0,
    cooldown_until        TEXT,
    updated_at            TEXT    NOT NULL,
    PRIMARY KEY (user_id, behavior_name)
);

CREATE INDEX IF NOT EXISTS idx_proactive_behavior_autonomy_user
    ON proactive_behavior_autonomy (user_id, updated_at DESC);

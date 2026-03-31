CREATE TABLE proactive_reminder_replay_reports (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    since TEXT NOT NULL,
    generated_at TEXT NOT NULL,
    sample_count INTEGER NOT NULL DEFAULT 0,
    historical_push_count INTEGER NOT NULL DEFAULT 0,
    replayed_push_count INTEGER NOT NULL DEFAULT 0,
    suppressed_count INTEGER NOT NULL DEFAULT 0,
    promoted_count INTEGER NOT NULL DEFAULT 0,
    action_shift_count INTEGER NOT NULL DEFAULT 0,
    historical_observed_reward_mean REAL NOT NULL DEFAULT 0,
    historical_estimated_push_reward_mean REAL NOT NULL DEFAULT 0,
    replayed_estimated_push_reward_mean REAL NOT NULL DEFAULT 0,
    action_shift_json TEXT,
    summary_json TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_proactive_reminder_replay_reports_user_generated_at
    ON proactive_reminder_replay_reports(user_id, generated_at DESC);

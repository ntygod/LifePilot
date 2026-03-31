-- 主动提醒策略版本表
-- 用于持久化每版调优后的策略参数，并将执行轮次/决策绑定到稳定版本

CREATE TABLE proactive_reminder_policy_versions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    config_signature TEXT NOT NULL,
    config_json TEXT NOT NULL,
    source TEXT NOT NULL,
    summary_json TEXT,
    activated_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (user_id, version),
    UNIQUE (user_id, config_signature)
);

CREATE INDEX idx_proactive_reminder_policy_versions_user_version
    ON proactive_reminder_policy_versions(user_id, version DESC);

CREATE INDEX idx_proactive_reminder_policy_versions_signature
    ON proactive_reminder_policy_versions(user_id, config_signature);

ALTER TABLE proactive_reminder_runs
    ADD COLUMN policy_version_id TEXT;

ALTER TABLE proactive_reminder_runs
    ADD COLUMN policy_version INTEGER;

ALTER TABLE proactive_reminder_decisions
    ADD COLUMN policy_version_id TEXT;

ALTER TABLE proactive_reminder_decisions
    ADD COLUMN policy_version INTEGER;

CREATE INDEX idx_proactive_reminder_decisions_policy_version
    ON proactive_reminder_decisions(policy_version_id, created_at DESC);

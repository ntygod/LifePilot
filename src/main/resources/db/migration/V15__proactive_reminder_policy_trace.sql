-- ============================================================
-- V10: 主动提醒学习策略追踪
-- ============================================================

CREATE TABLE proactive_reminder_policy_traces (
    decision_id TEXT PRIMARY KEY,
    base_action TEXT NOT NULL,
    opportunity_action TEXT NOT NULL,
    final_action TEXT NOT NULL,
    opportunity_adjusted INTEGER NOT NULL DEFAULT 0,
    action_adjusted INTEGER NOT NULL DEFAULT 0,
    training_example_count INTEGER NOT NULL DEFAULT 0,
    action_feedback_sample_count INTEGER NOT NULL DEFAULT 0,
    trace_json TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (decision_id) REFERENCES proactive_reminder_decisions(id) ON DELETE CASCADE
);

CREATE INDEX idx_proactive_reminder_policy_traces_final_action
    ON proactive_reminder_policy_traces(final_action, created_at DESC);

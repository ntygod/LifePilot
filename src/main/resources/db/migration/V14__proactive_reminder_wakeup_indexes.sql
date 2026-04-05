-- ============================================================
-- V9: 主动提醒延后唤醒查询索引
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_proactive_reminder_decisions_action_next_eval
    ON proactive_reminder_decisions(action, next_evaluation_at);

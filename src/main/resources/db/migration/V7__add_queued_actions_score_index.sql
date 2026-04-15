-- 补充排队动作表的 score 排序索引，覆盖 findPendingByUserId 查询

DROP INDEX IF EXISTS idx_proactive_queued_actions_user_shown;

CREATE INDEX IF NOT EXISTS idx_proactive_queued_actions_user_shown_score
    ON proactive_queued_actions (user_id, shown, score DESC, created_at DESC);

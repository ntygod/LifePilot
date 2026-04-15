-- 主动引擎目标追踪覆盖层 — 记录引擎对 L3 目标实体的追踪状态

CREATE TABLE IF NOT EXISTS proactive_goal_tracking (
    entity_id      TEXT    NOT NULL PRIMARY KEY,
    check_count    INTEGER NOT NULL DEFAULT 0,
    last_follow_up TEXT,
    updated_at     TEXT    NOT NULL
);

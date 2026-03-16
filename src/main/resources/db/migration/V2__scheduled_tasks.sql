-- ============================================================
-- V2: Agent 定时任务系统
-- ============================================================

CREATE TABLE scheduled_tasks (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    trigger_type      TEXT NOT NULL CHECK (trigger_type IN ('ONCE', 'CRON')),
    trigger_at        TEXT,                -- 一次性任务触发时间（ISO 8601）
    cron_expr         TEXT,                -- 周期性任务 cron 表达式
    action_json       TEXT NOT NULL,        -- TaskAction 序列化 JSON
    status            TEXT NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    error_message     TEXT,
    last_triggered_at TEXT,
    next_trigger_at   TEXT,
    metadata_json     TEXT,                -- 扩展元数据（如 {"scheduleId": "xxx"}）
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE INDEX idx_scheduled_tasks_status ON scheduled_tasks(status);
CREATE INDEX idx_scheduled_tasks_next_trigger ON scheduled_tasks(next_trigger_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_scheduled_tasks_metadata ON scheduled_tasks(metadata_json)
    WHERE metadata_json IS NOT NULL;

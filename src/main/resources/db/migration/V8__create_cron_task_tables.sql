-- Cron 定时任务表
CREATE TABLE IF NOT EXISTS cron_tasks (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    schedule    TEXT NOT NULL,
    instruction TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- Cron 任务执行日志表
CREATE TABLE IF NOT EXISTS cron_task_logs (
    id          TEXT PRIMARY KEY,
    task_id     TEXT NOT NULL REFERENCES cron_tasks(id) ON DELETE CASCADE,
    executed_at TEXT NOT NULL,
    status      TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    tokens_used INTEGER NOT NULL DEFAULT 0,
    summary     TEXT,
    created_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cron_task_logs_task_id ON cron_task_logs(task_id);
CREATE INDEX IF NOT EXISTS idx_cron_tasks_status ON cron_tasks(status);

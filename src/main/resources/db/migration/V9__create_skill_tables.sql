-- V9: 创建内置 Skill 业务表（待办、日程、习惯）

-- 待办事项表
CREATE TABLE IF NOT EXISTS todos (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    description TEXT,
    priority    TEXT NOT NULL DEFAULT 'MEDIUM',  -- HIGH / MEDIUM / LOW
    status      TEXT NOT NULL DEFAULT 'PENDING', -- PENDING / IN_PROGRESS / COMPLETED
    due_date    TEXT,                             -- ISO 8601
    tags_json   TEXT,                             -- JSON 数组
    created_at  TEXT NOT NULL,                    -- ISO 8601
    updated_at  TEXT NOT NULL                     -- ISO 8601
);

-- 日程表
CREATE TABLE IF NOT EXISTS schedules (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    start_time  TEXT NOT NULL,                    -- ISO 8601
    end_time    TEXT NOT NULL,                    -- ISO 8601
    location    TEXT,
    notes       TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- 日程时间索引（冲突检测优化）
CREATE INDEX IF NOT EXISTS idx_schedules_time ON schedules(start_time, end_time);

-- 习惯表
CREATE TABLE IF NOT EXISTS habits (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    frequency       TEXT NOT NULL,                -- DAILY / WEEKLY
    target_time     TEXT,                          -- HH:mm 格式
    current_streak  INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

-- 打卡记录表
CREATE TABLE IF NOT EXISTS habit_logs (
    id          TEXT PRIMARY KEY,
    habit_id    TEXT NOT NULL REFERENCES habits(id),
    checked_at  TEXT NOT NULL,                    -- ISO 8601
    created_at  TEXT NOT NULL
);

-- 打卡查询优化索引
CREATE INDEX IF NOT EXISTS idx_habit_logs_habit_checked ON habit_logs(habit_id, checked_at);

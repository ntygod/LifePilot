-- 主动引擎排队动作表 — 存储 QUEUE 级别的动作，下次用户对话时展示

CREATE TABLE IF NOT EXISTS proactive_queued_actions (
    id          TEXT    NOT NULL PRIMARY KEY,
    user_id     TEXT    NOT NULL,
    behavior    TEXT    NOT NULL,
    topic_key   TEXT    NOT NULL,
    title       TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    score       REAL    NOT NULL,
    metadata    TEXT,
    shown       INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL,
    shown_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_proactive_queued_actions_user_shown
    ON proactive_queued_actions (user_id, shown, created_at DESC);

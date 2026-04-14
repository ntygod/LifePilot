-- 意图记忆表 — 持久化用户目标、关注点和触发条件

CREATE TABLE IF NOT EXISTS proactive_intent_memory (
    id                   TEXT    NOT NULL PRIMARY KEY,
    user_id              TEXT    NOT NULL,
    intent_type          TEXT    NOT NULL,
    goal                 TEXT    NOT NULL,
    trigger_condition    TEXT,
    source_session_id    TEXT,
    status               TEXT    NOT NULL DEFAULT 'ACTIVE',
    check_count          INTEGER NOT NULL DEFAULT 0,
    created_at           TEXT    NOT NULL,
    expires_at           TEXT,
    triggered_at         TEXT,
    fulfilled_at         TEXT,
    updated_at           TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_proactive_intent_memory_user_status
    ON proactive_intent_memory (user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_proactive_intent_memory_expires
    ON proactive_intent_memory (status, expires_at);

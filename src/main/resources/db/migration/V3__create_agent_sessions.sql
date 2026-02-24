-- Agent 会话表
CREATE TABLE IF NOT EXISTS agent_sessions (
    id                      TEXT PRIMARY KEY,
    channel_id              TEXT NOT NULL,
    recent_turns_json       TEXT NOT NULL DEFAULT '[]',
    mentioned_entities_json TEXT NOT NULL DEFAULT '[]',
    active_task_context     TEXT,
    last_active_at          TEXT NOT NULL,
    total_turns             INTEGER NOT NULL DEFAULT 0,
    total_tokens_used       INTEGER NOT NULL DEFAULT 0,
    archived                INTEGER NOT NULL DEFAULT 0,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_sessions_channel
    ON agent_sessions(channel_id, last_active_at);
CREATE INDEX IF NOT EXISTS idx_agent_sessions_active
    ON agent_sessions(archived, last_active_at);

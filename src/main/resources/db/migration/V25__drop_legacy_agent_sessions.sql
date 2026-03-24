PRAGMA foreign_keys = OFF;

CREATE TABLE agent_traces_new (
    id                 TEXT PRIMARY KEY,
    session_id         TEXT NOT NULL,
    user_message       TEXT NOT NULL,
    final_output       TEXT,
    success            INTEGER NOT NULL DEFAULT 1,
    error_message      TEXT,
    termination_reason TEXT,
    total_steps        INTEGER NOT NULL DEFAULT 0,
    total_tokens       INTEGER NOT NULL DEFAULT 0,
    duration_ms        INTEGER NOT NULL DEFAULT 0,
    model_id           TEXT,
    parent_trace_id    TEXT,
    depth              INTEGER NOT NULL DEFAULT 0,
    created_at         TEXT NOT NULL
);

INSERT INTO agent_traces_new (
    id, session_id, user_message, final_output, success, error_message,
    termination_reason, total_steps, total_tokens, duration_ms,
    model_id, parent_trace_id, depth, created_at
)
SELECT
    id, session_id, user_message, final_output, success, error_message,
    termination_reason, total_steps, total_tokens, duration_ms,
    model_id, parent_trace_id, depth, created_at
FROM agent_traces;

DROP TABLE agent_traces;
ALTER TABLE agent_traces_new RENAME TO agent_traces;

CREATE INDEX idx_agent_traces_session ON agent_traces(session_id, created_at);
CREATE INDEX idx_agent_traces_time ON agent_traces(created_at);

DROP TABLE IF EXISTS agent_sessions;

PRAGMA foreign_keys = ON;

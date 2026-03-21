CREATE TABLE IF NOT EXISTS agent_checkpoints (
    session_id TEXT NOT NULL,
    channel TEXT NOT NULL,
    task_fingerprint TEXT NOT NULL,
    source_trace_id TEXT NOT NULL,
    state_json TEXT NOT NULL,
    failure_reason TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, channel, task_fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_updated_at
    ON agent_checkpoints(updated_at);

CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_source_trace_id
    ON agent_checkpoints(source_trace_id);

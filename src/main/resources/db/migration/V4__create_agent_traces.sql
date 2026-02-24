-- Agent 轨迹表
CREATE TABLE IF NOT EXISTS agent_traces (
    id                TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL,
    user_message      TEXT NOT NULL,
    final_output      TEXT,
    success           INTEGER NOT NULL DEFAULT 1,
    error_message     TEXT,
    termination_reason TEXT,
    total_steps       INTEGER NOT NULL DEFAULT 0,
    total_tokens      INTEGER NOT NULL DEFAULT 0,
    duration_ms       INTEGER NOT NULL DEFAULT 0,
    model_id          TEXT,
    parent_trace_id   TEXT,
    depth             INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES agent_sessions(id)
);

CREATE INDEX IF NOT EXISTS idx_agent_traces_session
    ON agent_traces(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_traces_time
    ON agent_traces(created_at);

-- Agent 轨迹步骤表
CREATE TABLE IF NOT EXISTS agent_trace_steps (
    id              TEXT PRIMARY KEY,
    trace_id        TEXT NOT NULL,
    step_index      INTEGER NOT NULL,
    phase_before    TEXT NOT NULL,
    phase_after     TEXT NOT NULL,
    action_type     TEXT NOT NULL,
    action_json     TEXT NOT NULL,
    tool_id         TEXT,
    tool_input_json TEXT,
    tool_output     TEXT,
    success         INTEGER NOT NULL DEFAULT 1,
    blocked         INTEGER NOT NULL DEFAULT 0,
    block_reason    TEXT,
    tokens_used     INTEGER NOT NULL DEFAULT 0,
    latency_ms      INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    FOREIGN KEY (trace_id) REFERENCES agent_traces(id)
);

CREATE INDEX IF NOT EXISTS idx_trace_steps_trace
    ON agent_trace_steps(trace_id, step_index);

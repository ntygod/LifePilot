CREATE TABLE session_workspace_items (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    kind            TEXT NOT NULL,
    title           TEXT NOT NULL,
    summary         TEXT NOT NULL,
    payload_json    TEXT,
    status          TEXT NOT NULL,
    priority        INTEGER NOT NULL DEFAULT 0,
    task_id         TEXT,
    source_trace_id TEXT,
    expires_at      TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX idx_workspace_session_status
    ON session_workspace_items(session_id, status, updated_at DESC);

CREATE INDEX idx_workspace_expires_at
    ON session_workspace_items(expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX idx_workspace_task
    ON session_workspace_items(session_id, task_id)
    WHERE task_id IS NOT NULL;

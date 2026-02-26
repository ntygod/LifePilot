-- V16__create_sandbox_executions.sql
-- 沙箱执行审计记录表

CREATE TABLE IF NOT EXISTS sandbox_executions (
    id                TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL,
    language          TEXT NOT NULL,
    code_hash         TEXT NOT NULL,
    code_length       INTEGER NOT NULL,
    booter_type       TEXT NOT NULL,
    validation_passed INTEGER NOT NULL,
    violation_count   INTEGER NOT NULL DEFAULT 0,
    exit_code         INTEGER,
    stdout_length     INTEGER,
    stderr_length     INTEGER,
    duration_ms       INTEGER,
    state             TEXT NOT NULL,
    error_message     TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE INDEX idx_sandbox_executions_session ON sandbox_executions(session_id);
CREATE INDEX idx_sandbox_executions_state ON sandbox_executions(state);
CREATE INDEX idx_sandbox_executions_created ON sandbox_executions(created_at);

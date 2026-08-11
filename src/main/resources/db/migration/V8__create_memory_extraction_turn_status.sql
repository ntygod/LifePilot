CREATE TABLE IF NOT EXISTS memory_extraction_turn_status (
    turn_id      TEXT PRIMARY KEY,
    session_id   TEXT NOT NULL,
    status       TEXT NOT NULL,
    reason       TEXT,
    started_at   TEXT NOT NULL,
    completed_at TEXT,
    updated_at   TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_extraction_turn_status_session
    ON memory_extraction_turn_status(session_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_memory_extraction_turn_status_status
    ON memory_extraction_turn_status(status, updated_at);

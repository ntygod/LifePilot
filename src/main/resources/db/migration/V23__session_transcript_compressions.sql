CREATE TABLE session_transcript_compressions (
    entry_id            TEXT PRIMARY KEY,
    session_id          TEXT NOT NULL,
    compression_level   INTEGER NOT NULL DEFAULT 0,
    compressed_content  TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_session_transcript_compressions_session
    ON session_transcript_compressions(session_id, compression_level, updated_at DESC);

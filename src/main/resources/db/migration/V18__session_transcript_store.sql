CREATE TABLE session_store (
    session_id               TEXT PRIMARY KEY,
    channel                  TEXT NOT NULL DEFAULT 'web',
    chat_type                TEXT NOT NULL DEFAULT 'chat',
    title                    TEXT NOT NULL,
    summary                  TEXT,
    message_count            INTEGER NOT NULL DEFAULT 0,
    is_pinned                INTEGER NOT NULL DEFAULT 0,
    archived                 INTEGER NOT NULL DEFAULT 0,
    last_message_at          TEXT,
    created_at               TEXT NOT NULL,
    updated_at               TEXT NOT NULL,
    last_activity_at         TEXT NOT NULL,
    provider_override        TEXT,
    model_override           TEXT,
    thinking_level           TEXT,
    reasoning_level          TEXT,
    config_json              TEXT NOT NULL DEFAULT '{}',
    context_tokens_estimate  INTEGER NOT NULL DEFAULT 0,
    input_tokens             INTEGER NOT NULL DEFAULT 0,
    output_tokens            INTEGER NOT NULL DEFAULT 0,
    total_tokens             INTEGER NOT NULL DEFAULT 0,
    compaction_count         INTEGER NOT NULL DEFAULT 0,
    memory_flush_at          TEXT,
    active_branch_id         TEXT NOT NULL DEFAULT 'main'
);

CREATE INDEX idx_session_store_updated_at
    ON session_store(updated_at DESC);

CREATE INDEX idx_session_store_last_activity_at
    ON session_store(last_activity_at DESC);

CREATE INDEX idx_session_store_channel
    ON session_store(channel, last_activity_at DESC);

CREATE TABLE session_transcript_entries (
    id                TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL,
    parent_id         TEXT,
    branch_id         TEXT NOT NULL DEFAULT 'main',
    entry_type        TEXT NOT NULL,
    role              TEXT,
    turn_id           TEXT,
    trace_id          TEXT,
    visible_to_model  INTEGER NOT NULL DEFAULT 1,
    visible_to_user   INTEGER NOT NULL DEFAULT 1,
    payload_json      TEXT NOT NULL,
    token_estimate    INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_session_transcript_entries_session_created
    ON session_transcript_entries(session_id, created_at, id);

CREATE INDEX idx_session_transcript_entries_trace
    ON session_transcript_entries(trace_id, created_at);

CREATE INDEX idx_session_transcript_entries_turn
    ON session_transcript_entries(turn_id, created_at);

CREATE INDEX idx_session_transcript_entries_branch
    ON session_transcript_entries(session_id, branch_id, created_at);

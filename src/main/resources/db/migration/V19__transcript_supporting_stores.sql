CREATE TABLE IF NOT EXISTS session_artifacts (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    source_entry_id TEXT,
    trace_id        TEXT,
    artifact_type   TEXT NOT NULL,
    title           TEXT,
    summary         TEXT,
    payload_json    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE,
    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_session_artifacts_session_id
    ON session_artifacts(session_id);

CREATE INDEX IF NOT EXISTS idx_session_artifacts_trace_id
    ON session_artifacts(trace_id);

CREATE INDEX IF NOT EXISTS idx_session_artifacts_source_entry_id
    ON session_artifacts(source_entry_id);


CREATE TABLE IF NOT EXISTS memory_documents (
    id                TEXT PRIMARY KEY,
    namespace         TEXT NOT NULL,
    doc_type          TEXT NOT NULL,
    title             TEXT NOT NULL,
    path_like_key     TEXT NOT NULL,
    content_markdown  TEXT NOT NULL,
    source_session_id TEXT,
    source_entry_id   TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    FOREIGN KEY (source_session_id) REFERENCES session_store(session_id) ON DELETE SET NULL,
    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL,
    UNIQUE(namespace, path_like_key)
);

CREATE INDEX IF NOT EXISTS idx_memory_documents_namespace
    ON memory_documents(namespace);

CREATE INDEX IF NOT EXISTS idx_memory_documents_doc_type
    ON memory_documents(doc_type);

CREATE INDEX IF NOT EXISTS idx_memory_documents_source_session
    ON memory_documents(source_session_id);


CREATE TABLE IF NOT EXISTS memory_document_chunks (
    id             TEXT PRIMARY KEY,
    document_id    TEXT NOT NULL,
    chunk_index    INTEGER NOT NULL,
    content_text   TEXT NOT NULL,
    token_estimate INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES memory_documents(id) ON DELETE CASCADE,
    UNIQUE(document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_memory_document_chunks_document_id
    ON memory_document_chunks(document_id);


CREATE TABLE IF NOT EXISTS context_reports (
    id                   TEXT PRIMARY KEY,
    session_id           TEXT NOT NULL,
    trace_id             TEXT,
    system_prompt_tokens INTEGER NOT NULL DEFAULT 0,
    transcript_tokens    INTEGER NOT NULL DEFAULT 0,
    memory_tokens        INTEGER NOT NULL DEFAULT 0,
    artifact_tokens      INTEGER NOT NULL DEFAULT 0,
    tool_schema_tokens   INTEGER NOT NULL DEFAULT 0,
    tool_result_tokens   INTEGER NOT NULL DEFAULT 0,
    pruning_applied      INTEGER NOT NULL DEFAULT 0,
    compaction_applied   INTEGER NOT NULL DEFAULT 0,
    context_window       INTEGER NOT NULL DEFAULT 0,
    reserved_tokens      INTEGER NOT NULL DEFAULT 0,
    payload_json         TEXT NOT NULL DEFAULT '{}',
    created_at           TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_context_reports_session_id
    ON context_reports(session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_context_reports_trace_id
    ON context_reports(trace_id);

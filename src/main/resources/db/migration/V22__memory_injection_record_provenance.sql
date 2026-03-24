ALTER TABLE memory_injection_records RENAME TO memory_injection_records_legacy;

CREATE TABLE memory_injection_records (
    id              TEXT PRIMARY KEY,
    source_entry_id TEXT,
    session_id      TEXT NOT NULL,
    entity_ids_json TEXT NOT NULL,
    entity_type     TEXT NOT NULL DEFAULT 'GENERAL',
    source_trace_id TEXT,
    created_at      TEXT NOT NULL,
    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE
);

INSERT INTO memory_injection_records (
    id, source_entry_id, session_id, entity_ids_json, entity_type, source_trace_id, created_at
)
SELECT legacy.id,
       CASE
           WHEN EXISTS (
               SELECT 1
               FROM session_transcript_entries entry
               WHERE entry.id = legacy.message_id
           ) THEN legacy.message_id
           ELSE NULL
       END,
       legacy.session_id,
       legacy.entity_ids_json,
       COALESCE(legacy.entity_type, 'GENERAL'),
       legacy.trace_id,
       legacy.created_at
FROM memory_injection_records_legacy legacy;

DROP TABLE memory_injection_records_legacy;

CREATE INDEX idx_injection_records_source_entry ON memory_injection_records(source_entry_id);
CREATE INDEX idx_injection_records_source_trace_type ON memory_injection_records(source_trace_id, entity_type);
CREATE INDEX idx_injection_records_session ON memory_injection_records(session_id);

DROP TABLE IF EXISTS message_feedback;

CREATE TABLE message_feedback (
    id         TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    type       TEXT NOT NULL CHECK (type IN ('like', 'dislike')),
    feedback   TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_message_feedback_message ON message_feedback(message_id);
CREATE INDEX idx_message_feedback_session ON message_feedback(session_id);


DROP TABLE IF EXISTS message_attachments;

CREATE TABLE message_attachments (
    id         TEXT PRIMARY KEY,
    message_id TEXT,
    session_id TEXT NOT NULL,
    file_name  TEXT NOT NULL,
    file_path  TEXT NOT NULL,
    file_size  INTEGER NOT NULL DEFAULT 0,
    mime_type  TEXT NOT NULL DEFAULT '',
    url        TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_message_attachments_message ON message_attachments(message_id);
CREATE INDEX idx_message_attachments_session ON message_attachments(session_id);
CREATE INDEX idx_message_attachments_created ON message_attachments(created_at DESC);

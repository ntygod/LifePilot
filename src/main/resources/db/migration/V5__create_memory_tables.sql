-- 记忆系统核心表：conversations（对话记录）和 messages（消息记录）

CREATE TABLE conversations (
    id         TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    goal       TEXT NOT NULL,
    summary    TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_conversations_session ON conversations(session_id);

CREATE TABLE messages (
    id                 TEXT PRIMARY KEY,
    conversation_id    TEXT NOT NULL REFERENCES conversations(id),
    role               TEXT NOT NULL,
    content            TEXT NOT NULL,
    compressed_content TEXT,
    compression_level  INTEGER NOT NULL DEFAULT 0,
    is_pinned          INTEGER NOT NULL DEFAULT 0,
    tool_call_json     TEXT,
    token_count        INTEGER DEFAULT 0,
    created_at         TEXT NOT NULL
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);
CREATE INDEX idx_messages_pinned ON messages(conversation_id) WHERE is_pinned = 1;

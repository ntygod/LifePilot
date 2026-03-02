-- V28: 创建消息附件表

-- 消息附件表：存储聊天消息中的附件（图片、PDF、文本文件等）
CREATE TABLE IF NOT EXISTS message_attachments (
    id TEXT PRIMARY KEY,
    message_id TEXT,
    session_id TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_size INTEGER NOT NULL DEFAULT 0,
    mime_type TEXT NOT NULL DEFAULT '',
    url TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE SET NULL,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_message_attachments_message 
    ON message_attachments(message_id);
CREATE INDEX IF NOT EXISTS idx_message_attachments_session 
    ON message_attachments(session_id);
CREATE INDEX IF NOT EXISTS idx_message_attachments_created 
    ON message_attachments(created_at DESC);

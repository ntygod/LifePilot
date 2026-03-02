-- V27: 创建消息反馈表
-- 用于支持用户对消息进行点赞/点踩反馈

CREATE TABLE IF NOT EXISTS message_feedback (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('like', 'dislike')),
    feedback TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_message_feedback_message 
    ON message_feedback(message_id);

CREATE INDEX IF NOT EXISTS idx_message_feedback_session 
    ON message_feedback(session_id);

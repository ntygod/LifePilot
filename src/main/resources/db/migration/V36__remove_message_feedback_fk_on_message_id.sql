-- V36: 移除 message_feedback 表对 chat_messages(id) 的外键约束
--
-- 原因：消息通过异步后处理写入 chat_messages，用户在消息流式输出完成后
-- 立即点击点赞/点踩时，chat_messages 行可能尚未持久化，导致
-- SQLITE_CONSTRAINT_FOREIGNKEY 错误。
--
-- 保留 session_id 外键（chat_sessions 在对话开始前同步创建，不存在时序问题）。
-- message_id 仅作为逻辑关联字段，不再做数据库级外键约束。

CREATE TABLE IF NOT EXISTS message_feedback_new (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('like', 'dislike')),
    feedback TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

INSERT INTO message_feedback_new (id, message_id, session_id, type, feedback, created_at)
SELECT id, message_id, session_id, type, feedback, created_at
FROM message_feedback;

DROP TABLE IF EXISTS message_feedback;
ALTER TABLE message_feedback_new RENAME TO message_feedback;

CREATE INDEX IF NOT EXISTS idx_message_feedback_message
    ON message_feedback(message_id);
CREATE INDEX IF NOT EXISTS idx_message_feedback_session
    ON message_feedback(session_id);

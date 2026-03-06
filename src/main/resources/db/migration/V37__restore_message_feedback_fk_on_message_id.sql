-- V37: 恢复 message_feedback 表对 chat_messages(id) 的外键约束
--
-- 原因：V36 移除 FK 是异步写入竞态的临时缓解措施。
-- 现在消息已改为同步持久化（done 事件发送前消息行已写入），
-- FK 约束可安全恢复。
--
-- 步骤：
-- 1. 清理 message_feedback 中 message_id 在 chat_messages 中不存在的孤立记录
-- 2. 重建表，恢复 message_id 对 chat_messages(id) 的外键约束

-- 清理孤立记录
DELETE FROM message_feedback
WHERE message_id NOT IN (SELECT id FROM chat_messages);

-- 重建表，恢复外键约束
CREATE TABLE IF NOT EXISTS message_feedback_new (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('like', 'dislike')),
    feedback TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE,
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

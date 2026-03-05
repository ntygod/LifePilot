-- V31: 创建 Web UI 对话历史消息表，并将反馈/附件从 memory.messages 外键解耦
--
-- 设计目标：
-- 1) Web UI 对话历史（保存/加载）只依赖 chat_sessions + chat_messages
-- 2) L1-L4 记忆系统（conversations/messages 等）与对话历史物理分离
-- 3) message_feedback / message_attachments 的 message_id 外键指向 chat_messages(id)

-- 1) 独立的对话历史消息表（仅承载 UI 侧历史，不承载记忆抽取所需的压缩/FTS 等）
CREATE TABLE IF NOT EXISTS chat_messages (
    id               TEXT PRIMARY KEY,
    session_id       TEXT NOT NULL,
    role             TEXT NOT NULL, -- user / assistant / system / tool（目前主要使用 user/assistant）
    content          TEXT NOT NULL,
    reasoning_summary TEXT,         -- assistant 消息可选
    trace_id         TEXT,          -- 可选，关联 agent trace
    created_at       TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_created
    ON chat_messages(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_role
    ON chat_messages(session_id, role, created_at);

-- 2) 将 message_feedback 的外键从 messages(id) 迁移到 chat_messages(id)
-- SQLite 无法直接修改外键，采用：新建表 -> 拷贝 -> 替换
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
FROM message_feedback
WHERE EXISTS (SELECT 1 FROM chat_messages cm WHERE cm.id = message_feedback.message_id);

DROP TABLE IF EXISTS message_feedback;
ALTER TABLE message_feedback_new RENAME TO message_feedback;

CREATE INDEX IF NOT EXISTS idx_message_feedback_message
    ON message_feedback(message_id);
CREATE INDEX IF NOT EXISTS idx_message_feedback_session
    ON message_feedback(session_id);

-- 3) 将 message_attachments 的外键从 messages(id) 迁移到 chat_messages(id)
CREATE TABLE IF NOT EXISTS message_attachments_new (
    id TEXT PRIMARY KEY,
    message_id TEXT,
    session_id TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_size INTEGER NOT NULL DEFAULT 0,
    mime_type TEXT NOT NULL DEFAULT '',
    url TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE SET NULL,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

INSERT INTO message_attachments_new (id, message_id, session_id, file_name, file_path, file_size, mime_type, url, created_at)
SELECT
    id,
    CASE
        WHEN message_id IS NOT NULL AND EXISTS (SELECT 1 FROM chat_messages cm WHERE cm.id = message_attachments.message_id)
            THEN message_id
        ELSE NULL
    END AS message_id,
    session_id,
    file_name,
    file_path,
    file_size,
    mime_type,
    url,
    created_at
FROM message_attachments
WHERE 1=1;

DROP TABLE IF EXISTS message_attachments;
ALTER TABLE message_attachments_new RENAME TO message_attachments;

CREATE INDEX IF NOT EXISTS idx_message_attachments_message
    ON message_attachments(message_id);
CREATE INDEX IF NOT EXISTS idx_message_attachments_session
    ON message_attachments(session_id);
CREATE INDEX IF NOT EXISTS idx_message_attachments_created
    ON message_attachments(created_at DESC);


-- Phase 2A: 文档产物元数据表
-- 与 message_attachments 的关系：
--   message_attachments 是 "消息附件挂载"（短生命周期，挂在 transcript entry 上）
--   documents 是 "文档产物资产"（长生命周期，跨会话可查询）
--   AI 生成的 docx 同时出现在两张表：documents 存元数据 + origin 来源，
--   message_attachments 负责让该 docx 在特定消息气泡里可见。

CREATE TABLE IF NOT EXISTS documents (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    entry_id TEXT,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    mime_type TEXT NOT NULL,
    origin TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_documents_session_id ON documents(session_id);
CREATE INDEX IF NOT EXISTS idx_documents_entry_id ON documents(entry_id);
CREATE INDEX IF NOT EXISTS idx_documents_origin ON documents(origin);

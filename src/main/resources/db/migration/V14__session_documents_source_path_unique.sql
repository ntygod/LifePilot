-- Phase 3A 补丁：确保同一 session 内 source_path 唯一（仅 NOT NULL 行参与），
-- 让 checkoutFromPath 的"同 session 同 path 只能有一份工作副本"不变量下沉到 DB 层。
-- 原 V13 的非唯一索引 idx_session_documents_source_path 保留（普通索引在部分 UNIQUE 之外仍有 point-query 价值）。

CREATE UNIQUE INDEX IF NOT EXISTS uk_session_documents_source_path
    ON session_documents(session_id, source_path)
    WHERE source_path IS NOT NULL;

-- Phase 3A: 文档编辑与版本管理
-- 扩展 session_documents 以承载工作副本状态，新建 document_versions 存版本链。

ALTER TABLE session_documents ADD COLUMN source_path TEXT;
ALTER TABLE session_documents ADD COLUMN latest_version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_session_documents_source_path ON session_documents(source_path);

CREATE TABLE IF NOT EXISTS document_versions (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    version_no INTEGER NOT NULL,
    file_path TEXT NOT NULL,
    source TEXT NOT NULL,
    patch_summary TEXT,
    diff_json TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES session_documents(id) ON DELETE CASCADE,
    UNIQUE (document_id, version_no)
);

CREATE INDEX IF NOT EXISTS idx_document_versions_document_id ON document_versions(document_id);
CREATE INDEX IF NOT EXISTS idx_document_versions_source ON document_versions(source);

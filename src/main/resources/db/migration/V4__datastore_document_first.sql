-- V4__datastore_document_first.sql
-- Datastore 文档优先改造：删除旧表，重建集合表，扩展 documents 表

-- 1. 清理旧 datastore 同步到知识库的残留文档
DELETE FROM document_chunks WHERE source_type = 'DATASTORE_DOCUMENT';
DELETE FROM documents WHERE source_type = 'DATASTORE_DOCUMENT';

-- 2. 清理旧 datastore 表
DROP TABLE IF EXISTS ds_documents_fts;
DROP TABLE IF EXISTS ds_documents;
DROP TABLE IF EXISTS ds_collections;

-- 3. 重建 ds_collections（新结构）
CREATE TABLE ds_collections (
    id                        TEXT PRIMARY KEY,
    name                      TEXT NOT NULL UNIQUE,
    description               TEXT,
    time_series               INTEGER NOT NULL DEFAULT 0,
    field_hints_json          TEXT,
    default_knowledge_base_id TEXT,
    created_by                TEXT,
    created_at                TEXT NOT NULL,
    updated_at                TEXT NOT NULL
);

-- 4. documents 表新增列
ALTER TABLE documents ADD COLUMN content TEXT;
ALTER TABLE documents ADD COLUMN recorded_at TEXT;

-- 5. 为 datastore 时序查询建索引
CREATE INDEX idx_documents_ds_recorded_at
    ON documents(source_datastore_id, recorded_at)
    WHERE source_datastore_id IS NOT NULL AND recorded_at IS NOT NULL;

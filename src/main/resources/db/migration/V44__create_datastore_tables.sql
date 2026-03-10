-- V44__create_datastore_tables.sql
-- 通用数据存储表结构

-- 集合表
CREATE TABLE IF NOT EXISTS ds_collections (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    type        TEXT NOT NULL DEFAULT 'DOCUMENT',
    properties_json TEXT,
    metadata_json   TEXT,
    created_by  TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- 文档表
CREATE TABLE IF NOT EXISTS ds_documents (
    id            TEXT PRIMARY KEY,
    collection_id TEXT NOT NULL REFERENCES ds_collections(id) ON DELETE CASCADE,
    data_json     TEXT NOT NULL DEFAULT '{}',
    recorded_at   TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_ds_documents_collection
    ON ds_documents(collection_id);
CREATE INDEX IF NOT EXISTS idx_ds_documents_recorded_at
    ON ds_documents(collection_id, recorded_at);

-- FTS5 全文索引（NOTE 类型文档）
CREATE VIRTUAL TABLE IF NOT EXISTS ds_documents_fts USING fts5(
    document_id,
    content,
    tokenize='unicode61'
);

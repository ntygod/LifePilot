-- V8: 创建知识库管理基础表

-- 知识库表
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id                  TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    embedding_model     TEXT NOT NULL,
    reranker_model      TEXT,
    chunking_strategy   TEXT NOT NULL DEFAULT 'smart',
    chunking_config_json TEXT NOT NULL DEFAULT '{}',
    document_count      INTEGER NOT NULL DEFAULT 0,
    total_chunks        INTEGER NOT NULL DEFAULT 0,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_knowledge_bases_name ON knowledge_bases(name);

-- 文档表
CREATE TABLE IF NOT EXISTS documents (
    id                   TEXT PRIMARY KEY,
    knowledge_base_id    TEXT NOT NULL,
    file_name            TEXT NOT NULL,
    file_path            TEXT NOT NULL,
    file_size            INTEGER NOT NULL DEFAULT 0,
    mime_type            TEXT NOT NULL DEFAULT '',
    content_hash         TEXT NOT NULL DEFAULT '',
    status               TEXT NOT NULL DEFAULT 'UPLOADING',
    chunk_count          INTEGER NOT NULL DEFAULT 0,
    entity_count         INTEGER NOT NULL DEFAULT 0,
    error_message        TEXT,
    last_processed_stage TEXT,
    metadata_json        TEXT NOT NULL DEFAULT '{}',
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_documents_kb_id ON documents(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_content_hash ON documents(knowledge_base_id, content_hash);

-- 文档分块表
CREATE TABLE IF NOT EXISTS document_chunks (
    id                    TEXT PRIMARY KEY,
    document_id           TEXT NOT NULL,
    knowledge_base_id     TEXT NOT NULL,
    content               TEXT NOT NULL,
    context_prefix        TEXT,
    chunk_index           INTEGER NOT NULL DEFAULT 0,
    start_offset          INTEGER NOT NULL DEFAULT 0,
    end_offset            INTEGER NOT NULL DEFAULT 0,
    token_count           INTEGER NOT NULL DEFAULT 0,
    content_hash          TEXT NOT NULL DEFAULT '',
    heading_hierarchy_json TEXT NOT NULL DEFAULT '[]',
    page_number           INTEGER NOT NULL DEFAULT 0,
    metadata_json         TEXT NOT NULL DEFAULT '{}',
    created_at            TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_document_chunks_doc_id ON document_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_kb_id ON document_chunks(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_hash ON document_chunks(content_hash);

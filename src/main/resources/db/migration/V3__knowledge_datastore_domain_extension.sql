-- ============================================================
-- V3: 知识库领域扩展（Datastore 挂载 + 来源感知文档 + 异步同步）
-- ============================================================

ALTER TABLE ds_collections
    ADD COLUMN projection_config_json TEXT NOT NULL DEFAULT '{}';

ALTER TABLE documents
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'FILE';

ALTER TABLE documents
    ADD COLUMN source_key TEXT NOT NULL DEFAULT '';

ALTER TABLE documents
    ADD COLUMN source_datastore_id TEXT;

ALTER TABLE documents
    ADD COLUMN source_collection_id TEXT;

ALTER TABLE documents
    ADD COLUMN source_ref_json TEXT NOT NULL DEFAULT '{}';

UPDATE documents
SET source_key = CASE
    WHEN source_key IS NULL OR source_key = '' THEN 'FILE:' || id
    ELSE source_key
END;

ALTER TABLE document_chunks
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'FILE';

ALTER TABLE document_chunks
    ADD COLUMN source_datastore_id TEXT;

ALTER TABLE document_chunks
    ADD COLUMN source_collection_id TEXT;

CREATE TABLE knowledge_base_datastores (
    knowledge_base_id TEXT NOT NULL,
    datastore_id      TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    PRIMARY KEY (knowledge_base_id, datastore_id),
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    FOREIGN KEY (datastore_id) REFERENCES ds_collections(id) ON DELETE CASCADE
);

CREATE TABLE session_datastores (
    session_id    TEXT NOT NULL,
    datastore_id  TEXT NOT NULL,
    PRIMARY KEY (session_id, datastore_id),
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE,
    FOREIGN KEY (datastore_id) REFERENCES ds_collections(id) ON DELETE CASCADE
);

CREATE TABLE knowledge_sync_jobs (
    id                TEXT PRIMARY KEY,
    job_type          TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,
    datastore_id      TEXT NOT NULL,
    source_key        TEXT,
    source_version    TEXT,
    payload_json      TEXT NOT NULL DEFAULT '{}',
    status            TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count     INTEGER NOT NULL DEFAULT 0,
    last_error        TEXT,
    available_at      TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    FOREIGN KEY (datastore_id) REFERENCES ds_collections(id) ON DELETE CASCADE
);

CREATE INDEX idx_documents_kb_source_key
    ON documents(knowledge_base_id, source_key);

CREATE INDEX idx_documents_kb_source_datastore
    ON documents(knowledge_base_id, source_datastore_id);

CREATE INDEX idx_documents_source_type
    ON documents(source_type);

CREATE INDEX idx_document_chunks_source_datastore
    ON document_chunks(source_datastore_id);

CREATE INDEX idx_document_chunks_source_type
    ON document_chunks(source_type);

CREATE INDEX idx_kb_datastores_datastore
    ON knowledge_base_datastores(datastore_id);

CREATE INDEX idx_session_datastores_session
    ON session_datastores(session_id);

CREATE INDEX idx_session_datastores_datastore
    ON session_datastores(datastore_id);

CREATE INDEX idx_knowledge_sync_jobs_available
    ON knowledge_sync_jobs(status, available_at, created_at);

CREATE INDEX idx_knowledge_sync_jobs_kb_datastore
    ON knowledge_sync_jobs(knowledge_base_id, datastore_id);

-- Parent-Child 分块架构 — 支持分层切片检索
-- parent_chunk_id: 子分块指向其父分块的 ID
-- chunk_level: 0=parent（大块，用于返回给 LLM），1=child（小块，用于向量检索）

ALTER TABLE document_chunks ADD COLUMN parent_chunk_id TEXT;

ALTER TABLE document_chunks ADD COLUMN chunk_level INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_document_chunks_parent ON document_chunks(parent_chunk_id);

CREATE INDEX idx_document_chunks_level ON document_chunks(chunk_level);

-- V13: 创建知识库搜索索引表（FTS5 全文索引 + sqlite-vec 向量索引）

-- FTS5 全文索引虚拟表（外部内容表模式，关联 document_chunks）
CREATE VIRTUAL TABLE IF NOT EXISTS document_chunks_fts USING fts5(
    content,
    knowledge_base_id UNINDEXED,
    document_id UNINDEXED,
    chunk_id UNINDEXED,
    content=document_chunks,
    content_rowid=rowid
);

-- 触发器：document_chunks 插入时同步 FTS5
CREATE TRIGGER IF NOT EXISTS document_chunks_ai AFTER INSERT ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES (new.rowid, new.content, new.knowledge_base_id, new.document_id, new.id);
END;

-- 触发器：document_chunks 删除时同步 FTS5
CREATE TRIGGER IF NOT EXISTS document_chunks_ad AFTER DELETE ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(document_chunks_fts, rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES ('delete', old.rowid, old.content, old.knowledge_base_id, old.document_id, old.id);
END;

-- sqlite-vec 向量索引虚拟表（chunk_embeddings）
-- 已迁移至 VectorIndexer 程序化创建，与 VectorSearcher 的 entity_embeddings 保持一致
-- sqlite-vec 不可用时降级为 JVM 暴力搜索，不阻塞 Flyway 迁移

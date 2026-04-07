-- FTS5 切换到 trigram tokenizer，天然支持 CJK 子串匹配
-- trigram 将文本拆分为所有 3 字符子序列，对中日韩文本无需外部分词器

-- 删除旧触发器
DROP TRIGGER IF EXISTS document_chunks_ai;

-- 删除旧 FTS5 表
DROP TABLE IF EXISTS document_chunks_fts;

-- 创建新 FTS5 表（trigram tokenizer）
CREATE VIRTUAL TABLE document_chunks_fts USING fts5(
    content,
    knowledge_base_id UNINDEXED,
    document_id UNINDEXED,
    chunk_id UNINDEXED,
    content=document_chunks,
    content_rowid=rowid,
    tokenize='trigram'
);

-- 重建自动同步触发器
CREATE TRIGGER document_chunks_ai AFTER INSERT ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES (new.rowid, new.content, new.knowledge_base_id, new.document_id, new.id);
END;

-- 从现有数据重建 FTS5 索引
INSERT INTO document_chunks_fts(rowid, content, knowledge_base_id, document_id, chunk_id)
SELECT rowid, content, knowledge_base_id, document_id, id
FROM document_chunks;

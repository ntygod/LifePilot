-- V25: 添加知识库 tags 字段

-- 添加 tags 字段（JSON 数组存储）
ALTER TABLE knowledge_bases ADD COLUMN tags TEXT DEFAULT '[]';

-- 添加索引支持标签查询
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_tags 
    ON knowledge_bases(tags);

-- 添加创建时间索引支持时间范围查询
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_created_at 
    ON knowledge_bases(created_at DESC);

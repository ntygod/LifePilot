-- V24__datastore_file_metadata.sql
-- 为 ds_documents 表添加文件元数据支持
-- 当用户向 Datastore 上传文件时，在 ds_documents 中创建"文件引用"记录，
-- 通过 source_type 列与普通结构化数据文档区分。

-- 添加 source_type 列，区分文档来源类型：
--   DATA     = 普通结构化数据文档（默认值，兼容历史数据）
--   FILE_REF = 用户上传文件的引用记录
ALTER TABLE ds_documents ADD COLUMN source_type TEXT NOT NULL DEFAULT 'DATA';

-- 添加 knowledge_document_id 列，记录该文件引用在知识库中对应的文档 ID，
-- 可空（普通 DATA 类型文档无需关联知识库文档）
ALTER TABLE ds_documents ADD COLUMN knowledge_document_id TEXT;

-- 为 source_type 创建索引，支持按文档来源类型快速过滤查询
CREATE INDEX IF NOT EXISTS idx_ds_documents_source_type ON ds_documents(source_type);

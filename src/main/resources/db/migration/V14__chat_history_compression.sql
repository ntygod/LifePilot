-- ============================================================
-- V14: 对话历史压缩支持
-- ============================================================
-- 问题：已结束的对话历史没有做压缩处理，前端全量加载所有步骤导致加载慢
-- 方案：新增 compressed_summary 字段存储压缩摘要，前端默认加载摘要

-- 1. chat_messages 新增压缩摘要字段
ALTER TABLE chat_messages ADD COLUMN compressed_summary TEXT;

-- 2. chat_sessions 新增压缩状态字段
ALTER TABLE chat_sessions ADD COLUMN compression_status TEXT NOT NULL DEFAULT 'NONE';
-- NONE: 未压缩, PENDING: 待压缩, COMPRESSED: 已压缩

-- 3. 为压缩状态创建索引（便于批量压缩任务查询）
CREATE INDEX IF NOT EXISTS idx_chat_sessions_compression ON chat_sessions(compression_status);

-- Plan 1 对话链路校正：
-- V16 给死表 conversations 加的 project_id 从未被代码消费（对话实际落在 session_store），
-- 现将 project_id 从 conversations 迁移到 session_store。
-- 先删 V16 的索引再 DROP COLUMN（SQLite 3.35+ 支持 DROP COLUMN，但前提是该列无索引）。

DROP INDEX IF EXISTS idx_conversations_project_id;
ALTER TABLE conversations DROP COLUMN project_id;

-- 将 project_id 挂到真实对话表 session_store：
-- 语义：NULL = 归属主账户；非 NULL = 归属具体项目
-- 级联策略由应用层 ProjectService 统一实现（与 Task 16 一致）
ALTER TABLE session_store ADD COLUMN project_id TEXT;

CREATE INDEX idx_session_store_project_id ON session_store(project_id)
    WHERE project_id IS NOT NULL;

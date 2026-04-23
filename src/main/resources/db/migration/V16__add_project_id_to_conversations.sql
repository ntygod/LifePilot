-- 对话归属项目：NULL = 归属主账户；非 NULL = 归属具体项目
-- 注意：不在此处添加 FK 到 projects(id)，引用完整性由应用层 ProjectService 保证，
-- 避免 V15 (projects) 与 V16 (conversations) 之间的删除循环锁死。
ALTER TABLE conversations ADD COLUMN project_id TEXT;

CREATE INDEX idx_conversations_project_id ON conversations(project_id)
    WHERE project_id IS NOT NULL;

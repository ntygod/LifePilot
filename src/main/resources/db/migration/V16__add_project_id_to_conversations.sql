-- 对话归属项目：NULL = 归属主账户；非 NULL = 归属具体项目
-- 注意：不在此处添加 FK 到 projects(id)，级联策略由应用层 ProjectService 统一实现，
-- 避免数据库隐式副作用（与 Plan Task 16 "项目删除级联清理"的职责归属一致）。
ALTER TABLE conversations ADD COLUMN project_id TEXT;

CREATE INDEX idx_conversations_project_id ON conversations(project_id)
    WHERE project_id IS NOT NULL;

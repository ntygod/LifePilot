-- Plan 2 定时任务项目归属：
-- NULL = 归属主账户；非 NULL = 归属具体项目
-- 级联策略由应用层实现（参考 Plan 1 V16 V17 既定风格），不加 FK 到 projects(id)
-- 避免删除循环和跨迁移依赖
ALTER TABLE cron_tasks ADD COLUMN project_id TEXT;

CREATE INDEX idx_cron_tasks_project_id ON cron_tasks(project_id)
    WHERE project_id IS NOT NULL;

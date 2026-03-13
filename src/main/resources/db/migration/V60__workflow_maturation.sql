-- 工作流模块成熟化：新增 trace_id 和 retry_count 列
-- 需求: 9.2（重试计数）, 12.3（可观测性 traceId）

ALTER TABLE workflow_instances ADD COLUMN trace_id TEXT;

ALTER TABLE workflow_step_logs ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;

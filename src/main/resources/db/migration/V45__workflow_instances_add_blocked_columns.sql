-- 工作流实例新增阻塞信息列：唤醒时间、阻塞步骤 ID、阻塞原因
-- 用于 WaitStep 自动唤醒和 ApprovalStep 超时监控

ALTER TABLE workflow_instances ADD COLUMN wake_up_at TEXT;
ALTER TABLE workflow_instances ADD COLUMN blocked_step_id TEXT;
ALTER TABLE workflow_instances ADD COLUMN blocked_reason TEXT;

-- 条件索引：仅索引 wake_up_at 非空的行，用于 WakeupScheduler 高效扫描到期实例
CREATE INDEX idx_workflow_instances_wake_up_at
    ON workflow_instances(state, wake_up_at)
    WHERE wake_up_at IS NOT NULL;

-- V20: 给 cron_task_logs 加 trigger_source 列，区分定时触发与人工"立即运行"。
-- 历史日志全部视为 cron 触发（由 CronScheduler 定时器写入）。
-- 人工触发会由新增的 POST /api/scheduled-tasks/{id}/run 端点写入 'manual'，
-- 前端日志列表据此打 "手动" tag，便于区分"定时失败 vs 人工重试"的运维语义。
ALTER TABLE cron_task_logs ADD COLUMN trigger_source TEXT NOT NULL DEFAULT 'cron';

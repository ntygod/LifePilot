-- V3__add_skill_ids_to_cron_tasks.sql
-- 为定时任务表新增 skill_ids 列，存储逗号分隔的 Skill ID 列表（如 "cron-scheduler,github-workflow"）

ALTER TABLE cron_tasks ADD COLUMN skill_ids TEXT;

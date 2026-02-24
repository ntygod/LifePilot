-- V1__init_schema.sql
-- 初始 Schema：验证 Flyway 迁移基础设施正常工作

CREATE TABLE IF NOT EXISTS schema_version_check (
    id         TEXT PRIMARY KEY,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

INSERT INTO schema_version_check (id, created_at)
VALUES ('init', datetime('now'));

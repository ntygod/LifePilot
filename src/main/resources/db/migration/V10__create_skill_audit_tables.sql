-- Skill 审计日志表
CREATE TABLE IF NOT EXISTS skill_audit_logs (
    id                TEXT PRIMARY KEY,           -- UUID
    skill_id          TEXT NOT NULL,              -- Skill ID
    event_type        TEXT NOT NULL,              -- REGISTERED/UNREGISTERED/GENERATED/CONFIRMED/REJECTED/ACTIVATED
    event_detail_json TEXT,                       -- 事件详情 JSON
    source_type       TEXT NOT NULL,              -- BUILTIN/USER_DEFINED/AUTO_GENERATED
    operator          TEXT NOT NULL,              -- 操作者（traceId 或 "system"）
    created_at        TEXT NOT NULL               -- ISO 8601
);

-- 按 Skill ID 查询索引
CREATE INDEX IF NOT EXISTS idx_skill_audit_logs_skill_id ON skill_audit_logs(skill_id);

-- 按时间范围查询索引
CREATE INDEX IF NOT EXISTS idx_skill_audit_logs_created_at ON skill_audit_logs(created_at);

-- V17：Skill 系统重构 —— skills 表重建为"安装元数据事实源"
-- 老 skills 表无代码写入，数据可抛。新表只存安装状态，内容实时从 SKILL.md 读。

DROP TABLE IF EXISTS skills;

CREATE TABLE skills (
    name                TEXT PRIMARY KEY,
    source_type         TEXT NOT NULL,
    source_uri          TEXT,
    file_path           TEXT NOT NULL,
    version             TEXT NOT NULL,
    enabled             INTEGER NOT NULL DEFAULT 1,
    marketplace_id      TEXT,
    checksum            TEXT,
    installed_at        TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    last_activated_at   TEXT,
    CHECK (source_type IN ('BUILTIN', 'USER_IMPORTED', 'MARKETPLACE', 'AUTO_GENERATED')),
    CHECK (enabled IN (0, 1))
);

CREATE INDEX idx_skills_source_enabled ON skills(source_type, enabled);
CREATE INDEX idx_skills_enabled ON skills(enabled);

-- 扩展市场：将 installed_skills 升级为 installed_extensions，支持多种扩展类型

-- 1. 创建新表
CREATE TABLE IF NOT EXISTS installed_extensions (
    id                  TEXT PRIMARY KEY,
    package_id          TEXT NOT NULL UNIQUE,
    type                TEXT NOT NULL,
    name                TEXT NOT NULL,
    version             TEXT NOT NULL,
    index_source_url    TEXT NOT NULL,
    repo_url            TEXT NOT NULL,
    file_path           TEXT NOT NULL,
    requirements_json   TEXT,
    security_report_json TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

-- 2. 迁移已有数据
INSERT INTO installed_extensions
    (id, package_id, type, name, version, index_source_url, repo_url,
     file_path, requirements_json, security_report_json, created_at, updated_at)
SELECT
    id, package_id, 'SKILL', name, version, index_source_url, repo_url,
    file_path, NULL, security_report_json, created_at, updated_at
FROM installed_skills;

-- 3. 删除旧表
DROP TABLE IF EXISTS installed_skills;

-- 4. 创建索引
CREATE INDEX idx_installed_extensions_type ON installed_extensions(type);
CREATE INDEX idx_installed_extensions_package_id ON installed_extensions(package_id);

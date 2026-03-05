-- Skill 市场：已安装的市场 Skill 记录
CREATE TABLE IF NOT EXISTS installed_skills (
    id                  TEXT PRIMARY KEY,
    package_id          TEXT NOT NULL UNIQUE,
    name                TEXT NOT NULL,
    version             TEXT NOT NULL,
    index_source_url    TEXT NOT NULL,
    repo_url            TEXT NOT NULL,
    file_path           TEXT NOT NULL,
    security_report_json TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

-- Skill 市场：索引缓存
CREATE TABLE IF NOT EXISTS marketplace_index_cache (
    id          TEXT PRIMARY KEY,
    source_url  TEXT NOT NULL UNIQUE,
    index_json  TEXT NOT NULL,
    fetched_at  TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

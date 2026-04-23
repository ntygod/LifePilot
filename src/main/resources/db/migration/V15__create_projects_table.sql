-- 项目（Project）— 用户显式创建的领域级任务容器。
-- project_id IS NULL 在关联表里代表"归属主账户"。
CREATE TABLE projects (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    instructions    TEXT NOT NULL DEFAULT '',
    isolation       TEXT NOT NULL DEFAULT 'ISOLATED'
                    CHECK (isolation IN ('ISOLATED', 'SHARED')),
    memory_space_id TEXT NOT NULL,                     -- 关联 memory_spaces.id
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    UNIQUE(name),
    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id) ON DELETE RESTRICT
);

CREATE INDEX idx_projects_created_at ON projects(created_at DESC);
CREATE INDEX idx_projects_memory_space ON projects(memory_space_id);

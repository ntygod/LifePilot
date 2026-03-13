-- 实体去重合并日志表
CREATE TABLE IF NOT EXISTS entity_merge_log (
    id                TEXT PRIMARY KEY,
    primary_entity_id TEXT NOT NULL,
    merged_entity_id  TEXT NOT NULL,
    similarity_score  REAL NOT NULL,
    merge_reason      TEXT,
    created_at        TEXT NOT NULL
);

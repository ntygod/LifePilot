-- 语义缓存表
CREATE TABLE IF NOT EXISTS semantic_cache (
    id                TEXT PRIMARY KEY,
    query_embedding   BLOB NOT NULL,
    response_text     TEXT NOT NULL,
    scene             TEXT NOT NULL,
    agent_phase       TEXT,
    model_name        TEXT NOT NULL,
    similarity_score  REAL NOT NULL DEFAULT 0.0,
    hit_count         INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL DEFAULT (datetime('now')),
    last_accessed_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

-- scene + agent_phase 复合索引（加速维度隔离查询）
CREATE INDEX IF NOT EXISTS idx_semantic_cache_scene_phase
    ON semantic_cache(scene, agent_phase);

-- last_accessed_at 索引（支持 LRU 淘汰排序）
CREATE INDEX IF NOT EXISTS idx_semantic_cache_last_accessed
    ON semantic_cache(last_accessed_at);

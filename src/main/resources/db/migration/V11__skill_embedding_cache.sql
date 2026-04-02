-- V11__skill_embedding_cache.sql
-- 缓存 Skill 向量索引结果，避免每次重启重复调用 embedding API

CREATE TABLE IF NOT EXISTS skill_embedding_cache (
    skill_id     TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    embedding    BLOB NOT NULL,
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (skill_id)
);

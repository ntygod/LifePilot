-- V51: 为语义缓存增加响应格式隔离键，避免自由文本与结构化输出串缓存
ALTER TABLE semantic_cache
    ADD COLUMN response_format_key TEXT NOT NULL DEFAULT 'text';

CREATE INDEX IF NOT EXISTS idx_semantic_cache_scene_phase_format
    ON semantic_cache(scene, agent_phase, response_format_key);

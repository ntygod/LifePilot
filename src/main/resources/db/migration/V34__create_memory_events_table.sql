-- V34: 创建记忆事件审计表 memory_events
--
-- 用于记录记忆系统中的关键操作事件（L1/L2/L3 等），仅作为可观测性与审计用途。
-- 设计原则：
-- - 轻量附加表，不影响主业务流程；
-- - 观测失败时允许静默降级（由 MemoryEventRecorder 负责）。

CREATE TABLE IF NOT EXISTS memory_events (
    id              TEXT PRIMARY KEY,            -- 事件 ID（UUID）
    event_type      TEXT NOT NULL,               -- 事件大类：FORMATION / RETRIEVAL / CONSOLIDATION / FORGETTING 等
    layer           TEXT NOT NULL,               -- 记忆层级：L1 / L2 / L3 / L4
    session_id      TEXT,                        -- 会话 ID（允许为空，兼容非会话场景）
    conversation_id TEXT,                        -- 对话 ID（通常对应 conversations.id）
    entity_id       TEXT,                        -- 关联实体/记忆 ID
    action          TEXT NOT NULL,               -- 具体动作：L1_APPEND / L1_FLUSH / GRAPH_UPSERT 等
    description     TEXT,                        -- 人类可读描述
    metadata_json   TEXT NOT NULL DEFAULT '{}',  -- 附加元数据（JSON 字符串）
    created_at      TEXT NOT NULL                -- 创建时间（ISO-8601 字符串）
);

CREATE INDEX IF NOT EXISTS idx_memory_events_session_time
    ON memory_events(session_id, created_at);

CREATE INDEX IF NOT EXISTS idx_memory_events_type_layer
    ON memory_events(event_type, layer);


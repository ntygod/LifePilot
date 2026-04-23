-- V15：记忆生命周期闭环
-- @author zsg
-- @since 2026-04-23

-- 1. memory_entities 生命周期字段
ALTER TABLE memory_entities ADD COLUMN lifecycle_state TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE memory_entities ADD COLUMN lifecycle_reason TEXT;
ALTER TABLE memory_entities ADD COLUMN expires_at TEXT;
ALTER TABLE memory_entities ADD COLUMN temporality TEXT NOT NULL DEFAULT 'PERSISTENT';
ALTER TABLE memory_entities ADD COLUMN succeeded_by TEXT;
ALTER TABLE memory_entities ADD COLUMN is_derived INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_entities ADD COLUMN derivation_sources TEXT;
CREATE INDEX idx_memory_entities_lifecycle ON memory_entities(lifecycle_state, expires_at);
CREATE INDEX idx_memory_entities_derived ON memory_entities(is_derived, lifecycle_state);

-- 旧 ARCHIVED 数据映射（若原 memory_entities 表无 status 列则跳过此步或报错）
UPDATE memory_entities SET lifecycle_state = 'ARCHIVED' WHERE status = 'ARCHIVED';

-- 2. memory_entity_provenances 失效标记
ALTER TABLE memory_entity_provenances ADD COLUMN status TEXT NOT NULL DEFAULT 'VALID';
ALTER TABLE memory_entity_provenances ADD COLUMN invalidated_at TEXT;

-- 3. L4 反向连接
ALTER TABLE preference_rules ADD COLUMN source_entity_id TEXT;
ALTER TABLE preference_rules ADD COLUMN deactivated_reason TEXT;
ALTER TABLE procedure_templates ADD COLUMN source_entity_id TEXT;
ALTER TABLE procedure_templates ADD COLUMN deactivated_reason TEXT;

-- 4. 反馈累计账本
CREATE TABLE memory_feedback_ledger (
    id TEXT PRIMARY KEY,
    entity_id TEXT NOT NULL,
    source TEXT NOT NULL,
    delta REAL NOT NULL,
    cumulative_score REAL NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_feedback_ledger_entity ON memory_feedback_ledger(entity_id, created_at);

-- 5. 再验证队列
CREATE TABLE memory_revalidation_queue (
    id TEXT PRIMARY KEY,
    entity_id TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING'
);
CREATE INDEX idx_revalidation_pending ON memory_revalidation_queue(status, created_at);

-- 6. 冲突裁决队列
CREATE TABLE conflict_resolution_queue (
    id TEXT PRIMARY KEY,
    new_entity_id TEXT NOT NULL,
    candidate_entity_ids TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    verdict TEXT,
    rationale TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    resolved_at TEXT
);
CREATE INDEX idx_conflict_queue_status ON conflict_resolution_queue(status, created_at);

-- 7. 派生实体重算队列
CREATE TABLE derivation_regeneration_queue (
    id TEXT PRIMARY KEY,
    derived_entity_id TEXT NOT NULL,
    trigger_source_entity_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TEXT NOT NULL,
    processed_at TEXT
);
CREATE INDEX idx_regeneration_pending ON derivation_regeneration_queue(status, created_at);

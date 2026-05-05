-- V34：记忆治理增强：项目 overlay、提取候选、投影 outbox
-- @author zsg
-- @since 2026-05-05

CREATE TABLE IF NOT EXISTS memory_entity_overlays (
    overlay_entity_id TEXT PRIMARY KEY,
    base_entity_id    TEXT NOT NULL,
    overlay_space_id  TEXT NOT NULL,
    origin_space_id   TEXT,
    overlay_kind      TEXT NOT NULL DEFAULT 'UPDATE',
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    FOREIGN KEY (overlay_entity_id) REFERENCES memory_entities(id) ON DELETE CASCADE,
    FOREIGN KEY (base_entity_id) REFERENCES memory_entities(id) ON DELETE CASCADE,
    FOREIGN KEY (overlay_space_id) REFERENCES memory_spaces(id),
    FOREIGN KEY (origin_space_id) REFERENCES memory_spaces(id)
);

CREATE INDEX IF NOT EXISTS idx_memory_entity_overlays_base
    ON memory_entity_overlays(base_entity_id, overlay_space_id);
CREATE INDEX IF NOT EXISTS idx_memory_entity_overlays_space
    ON memory_entity_overlays(overlay_space_id, overlay_kind);

CREATE TABLE IF NOT EXISTS memory_extraction_candidates (
    id                  TEXT PRIMARY KEY,
    session_id          TEXT NOT NULL,
    turn_id             TEXT,
    source_entry_id     TEXT,
    target_space_id     TEXT,
    operation           TEXT NOT NULL,
    entity_name         TEXT NOT NULL,
    entity_type         TEXT NOT NULL,
    decision_json       TEXT NOT NULL,
    candidate_status    TEXT NOT NULL DEFAULT 'VALIDATED',
    validation_status   TEXT NOT NULL DEFAULT 'VALIDATED',
    rejection_reason    TEXT,
    persisted_entity_id TEXT,
    base_entity_id      TEXT,
    error_message       TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_extraction_candidates_turn
    ON memory_extraction_candidates(turn_id, created_at);
CREATE INDEX IF NOT EXISTS idx_memory_extraction_candidates_status
    ON memory_extraction_candidates(candidate_status, updated_at);
CREATE INDEX IF NOT EXISTS idx_memory_extraction_candidates_entity
    ON memory_extraction_candidates(target_space_id, entity_type, entity_name);

CREATE TABLE IF NOT EXISTS memory_projection_outbox (
    id              TEXT PRIMARY KEY,
    aggregate_type  TEXT NOT NULL,
    aggregate_id    TEXT NOT NULL,
    projection_type TEXT NOT NULL,
    operation       TEXT NOT NULL,
    payload_json    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT,
    last_error      TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    processed_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_memory_projection_outbox_status
    ON memory_projection_outbox(status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_memory_projection_outbox_aggregate
    ON memory_projection_outbox(aggregate_type, aggregate_id);

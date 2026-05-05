-- V35：记忆数据质量契约
-- @author zsg
-- @since 2026-05-05

ALTER TABLE memory_entities ADD COLUMN evidence_kind TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE memory_entities ADD COLUMN trust_level TEXT NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE memory_entities ADD COLUMN trust_score REAL NOT NULL DEFAULT 0.0;
ALTER TABLE memory_entities ADD COLUMN evidence_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_entities ADD COLUMN last_verified_at TEXT;

CREATE INDEX idx_memory_entities_trust
    ON memory_entities(trust_level, trust_score);
CREATE INDEX idx_memory_entities_evidence
    ON memory_entities(evidence_kind, updated_at);

ALTER TABLE memory_entity_provenances ADD COLUMN evidence_kind TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE memory_entity_provenances ADD COLUMN trust_score REAL NOT NULL DEFAULT 0.0;
ALTER TABLE memory_entity_provenances ADD COLUMN trust_level TEXT NOT NULL DEFAULT 'UNVERIFIED';

ALTER TABLE memory_extraction_candidates ADD COLUMN evidence_kind TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE memory_extraction_candidates ADD COLUMN trust_level TEXT NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE memory_extraction_candidates ADD COLUMN trust_score REAL NOT NULL DEFAULT 0.0;
ALTER TABLE memory_extraction_candidates ADD COLUMN evidence_excerpt TEXT;

DROP VIEW IF EXISTS temporal_entities;

CREATE VIEW temporal_entities AS
WITH latest_entity_provenance AS (
    SELECT p.version_id, p.source_conversation_id
    FROM memory_entity_provenances p
    JOIN (
        SELECT version_id, MAX(created_at) AS max_created_at
        FROM memory_entity_provenances
        GROUP BY version_id
    ) latest
      ON latest.version_id = p.version_id
     AND latest.max_created_at = p.created_at
)
SELECT
    me.id AS id,
    me.space_id AS space_id,
    me.memory_scope AS memory_scope,
    me.entity_type AS type,
    me.canonical_name AS name,
    me.reality_type AS reality_type,
    mev.id AS version_id,
    mev.description AS description,
    mev.properties_json AS properties_json,
    mev.version_no AS version,
    mev.is_current AS is_current,
    mev.valid_from AS valid_from,
    mev.valid_to AS valid_to,
    lep.source_conversation_id AS source_conversation_id,
    mev.extraction_confidence AS extraction_confidence,
    mev.importance_score AS importance_score,
    me.access_count AS access_count,
    me.last_accessed_at AS last_accessed_at,
    me.created_at AS created_at,
    mev.updated_at AS updated_at,
    me.lifecycle_state AS lifecycle_state,
    me.lifecycle_reason AS lifecycle_reason,
    me.expires_at AS expires_at,
    me.temporality AS temporality,
    me.succeeded_by AS succeeded_by,
    me.is_derived AS is_derived,
    me.derivation_sources AS derivation_sources,
    me.evidence_kind AS evidence_kind,
    me.trust_level AS trust_level,
    me.trust_score AS trust_score,
    me.evidence_count AS evidence_count,
    me.last_verified_at AS last_verified_at
FROM memory_entities me
JOIN memory_entity_versions mev ON mev.entity_id = me.id
LEFT JOIN latest_entity_provenance lep ON lep.version_id = mev.id
WHERE me.status <> 'DELETED';

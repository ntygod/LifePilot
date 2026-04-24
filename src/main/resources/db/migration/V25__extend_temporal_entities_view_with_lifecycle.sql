-- V16：重建 temporal_entities 视图以包含 V15 新增生命周期字段
-- 旧视图定义在 V1 中，SQLite 不支持 ALTER VIEW，需要 DROP + CREATE。
-- V15 已经 applied，不能在 V15 中追加此变更（会破坏 checksum），故独立迁移。
-- @author zsg
-- @since 2026-04-23

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
    me.derivation_sources AS derivation_sources
FROM memory_entities me
JOIN memory_entity_versions mev ON mev.entity_id = me.id
LEFT JOIN latest_entity_provenance lep ON lep.version_id = mev.id
WHERE me.status <> 'DELETED';

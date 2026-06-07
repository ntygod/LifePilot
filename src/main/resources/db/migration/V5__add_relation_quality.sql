-- ============================================================
-- V5: 关系质量门（relation-quality-gate）
-- 给 memory_relations 增加质量治理字段（镜像实体），并重建 temporal_relations 视图暴露之。
-- trust_score 可空、无默认：NULL 标识历史未评分关系（消费门控放行），区别于已评分低分。
-- ============================================================

ALTER TABLE memory_relations ADD COLUMN evidence_kind TEXT;
ALTER TABLE memory_relations ADD COLUMN trust_level TEXT;
ALTER TABLE memory_relations ADD COLUMN trust_score REAL;

DROP VIEW temporal_relations;

CREATE VIEW temporal_relations AS
WITH latest_relation_provenance AS (
    SELECT p.version_id, p.source_conversation_id
    FROM memory_relation_provenances p
    JOIN (
        SELECT version_id, MAX(created_at) AS max_created_at
        FROM memory_relation_provenances
        GROUP BY version_id
    ) latest
      ON latest.version_id = p.version_id
     AND latest.max_created_at = p.created_at
)
SELECT
    mr.id AS id,
    mr.space_id AS space_id,
    mr.source_entity_id AS source_entity_id,
    mr.target_entity_id AS target_entity_id,
    mr.relation_type AS relation_type,
    mr.reality_type AS reality_type,
    mrv.id AS version_id,
    mrv.strength AS strength,
    mrv.properties_json AS properties_json,
    mrv.valid_from AS valid_from,
    mrv.valid_to AS valid_to,
    lrp.source_conversation_id AS source_conversation_id,
    mr.created_at AS created_at,
    mr.evidence_kind AS evidence_kind,
    mr.trust_level AS trust_level,
    mr.trust_score AS trust_score
FROM memory_relations mr
JOIN memory_relation_versions mrv ON mrv.relation_id = mr.id
LEFT JOIN latest_relation_provenance lrp ON lrp.version_id = mrv.id
WHERE mr.status <> 'DELETED';

ALTER TABLE temporal_relations RENAME TO temporal_relations_legacy;
ALTER TABLE temporal_entities RENAME TO temporal_entities_legacy;

CREATE TABLE memory_entities (
    id               TEXT PRIMARY KEY,
    space_id         TEXT NOT NULL,
    memory_scope     TEXT NOT NULL,
    entity_type      TEXT NOT NULL,
    canonical_name   TEXT NOT NULL,
    normalized_name  TEXT NOT NULL,
    reality_type     TEXT NOT NULL DEFAULT 'UNKNOWN',
    status           TEXT NOT NULL DEFAULT 'ACTIVE',
    access_count     INTEGER NOT NULL DEFAULT 0,
    last_accessed_at TEXT,
    first_seen_at    TEXT NOT NULL,
    last_seen_at     TEXT NOT NULL,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL,
    FOREIGN KEY (space_id) REFERENCES memory_spaces(id)
);

CREATE INDEX idx_memory_entities_scope ON memory_entities(space_id, memory_scope, entity_type);
CREATE INDEX idx_memory_entities_name ON memory_entities(normalized_name, entity_type);
CREATE INDEX idx_memory_entities_status ON memory_entities(status);

CREATE TABLE memory_entity_versions (
    id                    TEXT PRIMARY KEY,
    entity_id             TEXT NOT NULL,
    version_no            INTEGER NOT NULL,
    description           TEXT,
    properties_json       TEXT,
    extraction_confidence REAL NOT NULL DEFAULT 0.0,
    importance_score      REAL NOT NULL DEFAULT 0.5,
    is_current            INTEGER NOT NULL DEFAULT 1,
    valid_from            TEXT NOT NULL,
    valid_to              TEXT,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL,
    FOREIGN KEY (entity_id) REFERENCES memory_entities(id) ON DELETE CASCADE,
    UNIQUE (entity_id, version_no)
);

CREATE UNIQUE INDEX idx_memory_entity_versions_current
    ON memory_entity_versions(entity_id)
    WHERE is_current = 1;
CREATE INDEX idx_memory_entity_versions_valid
    ON memory_entity_versions(valid_from, valid_to, is_current);

CREATE TABLE memory_entity_provenances (
    id                     TEXT PRIMARY KEY,
    entity_id              TEXT NOT NULL,
    version_id             TEXT,
    origin_type            TEXT NOT NULL DEFAULT 'UNKNOWN',
    source_reference       TEXT,
    source_conversation_id TEXT,
    source_session_id      TEXT,
    source_turn_id         TEXT,
    source_entry_id        TEXT,
    source_document_id     TEXT,
    source_knowledge_base_id TEXT,
    source_datastore_id    TEXT,
    source_collection_id   TEXT,
    evidence_excerpt       TEXT,
    evidence_hash          TEXT,
    confidence             REAL NOT NULL DEFAULT 0.0,
    created_at             TEXT NOT NULL,
    FOREIGN KEY (entity_id) REFERENCES memory_entities(id) ON DELETE CASCADE,
    FOREIGN KEY (version_id) REFERENCES memory_entity_versions(id) ON DELETE CASCADE
);

CREATE INDEX idx_memory_entity_provenances_entity ON memory_entity_provenances(entity_id, created_at DESC);
CREATE INDEX idx_memory_entity_provenances_version ON memory_entity_provenances(version_id, created_at DESC);
CREATE INDEX idx_memory_entity_provenances_turn ON memory_entity_provenances(source_turn_id);
CREATE INDEX idx_memory_entity_provenances_document ON memory_entity_provenances(source_document_id);

CREATE TABLE memory_relations (
    id               TEXT PRIMARY KEY,
    space_id         TEXT NOT NULL,
    source_entity_id TEXT NOT NULL,
    target_entity_id TEXT NOT NULL,
    relation_type    TEXT NOT NULL,
    reality_type     TEXT NOT NULL DEFAULT 'UNKNOWN',
    status           TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL,
    FOREIGN KEY (space_id) REFERENCES memory_spaces(id),
    FOREIGN KEY (source_entity_id) REFERENCES memory_entities(id),
    FOREIGN KEY (target_entity_id) REFERENCES memory_entities(id)
);

CREATE INDEX idx_memory_relations_source ON memory_relations(source_entity_id, status);
CREATE INDEX idx_memory_relations_target ON memory_relations(target_entity_id, status);

CREATE TABLE memory_relation_versions (
    id             TEXT PRIMARY KEY,
    relation_id    TEXT NOT NULL,
    version_no     INTEGER NOT NULL,
    strength       REAL NOT NULL DEFAULT 0.5,
    properties_json TEXT,
    is_current     INTEGER NOT NULL DEFAULT 1,
    valid_from     TEXT NOT NULL,
    valid_to       TEXT,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL,
    FOREIGN KEY (relation_id) REFERENCES memory_relations(id) ON DELETE CASCADE,
    UNIQUE (relation_id, version_no)
);

CREATE UNIQUE INDEX idx_memory_relation_versions_current
    ON memory_relation_versions(relation_id)
    WHERE is_current = 1;

CREATE TABLE memory_relation_provenances (
    id                     TEXT PRIMARY KEY,
    relation_id            TEXT NOT NULL,
    version_id             TEXT,
    origin_type            TEXT NOT NULL DEFAULT 'UNKNOWN',
    source_reference       TEXT,
    source_conversation_id TEXT,
    source_session_id      TEXT,
    source_turn_id         TEXT,
    source_document_id     TEXT,
    source_knowledge_base_id TEXT,
    source_datastore_id    TEXT,
    source_collection_id   TEXT,
    confidence             REAL NOT NULL DEFAULT 0.0,
    created_at             TEXT NOT NULL,
    FOREIGN KEY (relation_id) REFERENCES memory_relations(id) ON DELETE CASCADE,
    FOREIGN KEY (version_id) REFERENCES memory_relation_versions(id) ON DELETE CASCADE
);

CREATE INDEX idx_memory_relation_provenances_relation
    ON memory_relation_provenances(relation_id, created_at DESC);

INSERT INTO memory_entities (
    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
    reality_type, status, access_count, last_accessed_at,
    first_seen_at, last_seen_at, created_at, updated_at
)
SELECT
    id,
    CASE
        WHEN type = 'EXPERIENCE' THEN 'memory-space-experience-default'
        ELSE 'memory-space-personal-default'
    END,
    CASE
        WHEN type = 'EXPERIENCE' THEN 'AGENT_EXPERIENCE'
        WHEN type IN ('PREFERENCE', 'HABIT', 'GOAL', 'SKILL') THEN 'USER_PROFILE'
        ELSE 'USER_FACT'
    END,
    type,
    name,
    lower(trim(name)),
    'UNKNOWN',
    CASE
        WHEN is_current = 1 THEN 'ACTIVE'
        ELSE 'ARCHIVED'
    END,
    access_count,
    last_accessed_at,
    created_at,
    updated_at,
    created_at,
    updated_at
FROM temporal_entities_legacy;

INSERT INTO memory_entity_versions (
    id, entity_id, version_no, description, properties_json,
    extraction_confidence, importance_score, is_current,
    valid_from, valid_to, created_at, updated_at
)
SELECT
    id,
    id,
    version,
    description,
    properties_json,
    extraction_confidence,
    importance_score,
    is_current,
    valid_from,
    valid_to,
    created_at,
    updated_at
FROM temporal_entities_legacy;

INSERT INTO memory_entity_provenances (
    id, entity_id, version_id, origin_type, source_reference, source_conversation_id,
    confidence, created_at
)
SELECT
    'prov:' || id,
    id,
    id,
    'UNKNOWN',
    source_conversation_id,
    source_conversation_id,
    extraction_confidence,
    updated_at
FROM temporal_entities_legacy
WHERE source_conversation_id IS NOT NULL AND trim(source_conversation_id) <> '';

INSERT INTO memory_relations (
    id, space_id, source_entity_id, target_entity_id, relation_type,
    reality_type, status, created_at, updated_at
)
SELECT
    tr.id,
    COALESCE(se.space_id, 'memory-space-personal-default'),
    tr.source_entity_id,
    tr.target_entity_id,
    tr.relation_type,
    'UNKNOWN',
    CASE
        WHEN tr.valid_to IS NULL THEN 'ACTIVE'
        ELSE 'ARCHIVED'
    END,
    tr.created_at,
    tr.created_at
FROM temporal_relations_legacy tr
LEFT JOIN memory_entities se ON se.id = tr.source_entity_id;

INSERT INTO memory_relation_versions (
    id, relation_id, version_no, strength, properties_json,
    is_current, valid_from, valid_to, created_at, updated_at
)
SELECT
    tr.id,
    tr.id,
    1,
    tr.strength,
    tr.properties_json,
    CASE
        WHEN tr.valid_to IS NULL THEN 1
        ELSE 0
    END,
    tr.valid_from,
    tr.valid_to,
    tr.created_at,
    tr.created_at
FROM temporal_relations_legacy tr;

INSERT INTO memory_relation_provenances (
    id, relation_id, version_id, origin_type, source_reference, source_conversation_id,
    confidence, created_at
)
SELECT
    'prov:' || tr.id,
    tr.id,
    tr.id,
    'UNKNOWN',
    tr.source_conversation_id,
    tr.source_conversation_id,
    tr.strength,
    tr.created_at
FROM temporal_relations_legacy tr
WHERE tr.source_conversation_id IS NOT NULL AND trim(tr.source_conversation_id) <> '';

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
    mev.updated_at AS updated_at
FROM memory_entities me
JOIN memory_entity_versions mev ON mev.entity_id = me.id
LEFT JOIN latest_entity_provenance lep ON lep.version_id = mev.id
WHERE me.status <> 'DELETED';

DROP VIEW IF EXISTS temporal_relations;
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
    mr.created_at AS created_at
FROM memory_relations mr
JOIN memory_relation_versions mrv ON mrv.relation_id = mr.id
LEFT JOIN latest_relation_provenance lrp ON lrp.version_id = mrv.id
WHERE mr.status <> 'DELETED';

DROP TABLE temporal_relations_legacy;
DROP TABLE temporal_entities_legacy;

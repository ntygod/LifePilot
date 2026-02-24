-- V7: 创建语义记忆表 — temporal_entities 和 temporal_relations

-- temporal_entities 表：时序知识图谱节点
CREATE TABLE temporal_entities (
    id                      TEXT PRIMARY KEY,
    type                    TEXT NOT NULL,
    name                    TEXT NOT NULL,
    description             TEXT,
    properties_json         TEXT,
    version                 INTEGER NOT NULL DEFAULT 1,
    is_current              INTEGER NOT NULL DEFAULT 1,
    valid_from              TEXT NOT NULL,
    valid_to                TEXT,
    source_conversation_id  TEXT REFERENCES conversations(id),
    extraction_confidence   REAL DEFAULT 0.0,
    importance_score        REAL DEFAULT 0.5,
    access_count            INTEGER DEFAULT 0,
    last_accessed_at        TEXT,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

CREATE INDEX idx_entities_name_type ON temporal_entities(name, type);
CREATE INDEX idx_entities_current ON temporal_entities(is_current) WHERE is_current = 1;
CREATE INDEX idx_entities_type_valid ON temporal_entities(type, valid_from, valid_to);
CREATE INDEX idx_entities_importance ON temporal_entities(importance_score, access_count) WHERE is_current = 1;
CREATE INDEX idx_entities_source ON temporal_entities(source_conversation_id) WHERE source_conversation_id IS NOT NULL;

-- temporal_relations 表：时序知识图谱边
CREATE TABLE temporal_relations (
    id                      TEXT PRIMARY KEY,
    source_entity_id        TEXT NOT NULL REFERENCES temporal_entities(id),
    target_entity_id        TEXT NOT NULL REFERENCES temporal_entities(id),
    relation_type           TEXT NOT NULL,
    strength                REAL DEFAULT 0.5,
    properties_json         TEXT,
    valid_from              TEXT NOT NULL,
    valid_to                TEXT,
    source_conversation_id  TEXT REFERENCES conversations(id),
    created_at              TEXT NOT NULL
);

CREATE INDEX idx_relations_source ON temporal_relations(source_entity_id, relation_type);
CREATE INDEX idx_relations_target ON temporal_relations(target_entity_id, relation_type);
CREATE INDEX idx_relations_valid ON temporal_relations(valid_from, valid_to) WHERE valid_to IS NULL;

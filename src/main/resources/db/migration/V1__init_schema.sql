-- ============================================================
-- V1: 知微（ZhiWei）统一初始化脚本
-- 合并 V1–V25 全部迁移，保留每张表的最终结构。
-- ============================================================

-- ============================================================
-- 一、会话与对话
-- ============================================================

CREATE TABLE session_store (
    session_id               TEXT PRIMARY KEY,
    channel                  TEXT NOT NULL DEFAULT 'web',
    chat_type                TEXT NOT NULL DEFAULT 'chat',
    title                    TEXT NOT NULL,
    summary                  TEXT,
    message_count            INTEGER NOT NULL DEFAULT 0,
    is_pinned                INTEGER NOT NULL DEFAULT 0,
    archived                 INTEGER NOT NULL DEFAULT 0,
    last_message_at          TEXT,
    created_at               TEXT NOT NULL,
    updated_at               TEXT NOT NULL,
    last_activity_at         TEXT NOT NULL,
    provider_override        TEXT,
    model_override           TEXT,
    thinking_level           TEXT,
    reasoning_level          TEXT,
    config_json              TEXT NOT NULL DEFAULT '{}',
    context_tokens_estimate  INTEGER NOT NULL DEFAULT 0,
    input_tokens             INTEGER NOT NULL DEFAULT 0,
    output_tokens            INTEGER NOT NULL DEFAULT 0,
    total_tokens             INTEGER NOT NULL DEFAULT 0,
    compaction_count         INTEGER NOT NULL DEFAULT 0,
    memory_flush_at          TEXT,
    active_branch_id         TEXT NOT NULL DEFAULT 'main'
);

CREATE INDEX idx_session_store_channel
    ON session_store(channel, last_activity_at DESC);
CREATE INDEX idx_session_store_last_activity_at
    ON session_store(last_activity_at DESC);
CREATE INDEX idx_session_store_updated_at
    ON session_store(updated_at DESC);

CREATE TABLE session_transcript_entries (
    id                TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL,
    parent_id         TEXT,
    branch_id         TEXT NOT NULL DEFAULT 'main',
    entry_type        TEXT NOT NULL,
    role              TEXT,
    turn_id           TEXT,
    trace_id          TEXT,
    visible_to_model  INTEGER NOT NULL DEFAULT 1,
    visible_to_user   INTEGER NOT NULL DEFAULT 1,
    payload_json      TEXT NOT NULL,
    token_estimate    INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_session_transcript_entries_branch
    ON session_transcript_entries(session_id, branch_id, created_at);
CREATE INDEX idx_session_transcript_entries_session_created
    ON session_transcript_entries(session_id, created_at, id);
CREATE INDEX idx_session_transcript_entries_trace
    ON session_transcript_entries(trace_id, created_at);
CREATE INDEX idx_session_transcript_entries_turn
    ON session_transcript_entries(turn_id, created_at);

CREATE VIRTUAL TABLE session_transcript_entries_fts USING fts5(
    entry_id UNINDEXED,
    session_id UNINDEXED,
    role UNINDEXED,
    content,
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TABLE session_transcript_compressions (
    entry_id            TEXT PRIMARY KEY,
    session_id          TEXT NOT NULL,
    compression_level   INTEGER NOT NULL DEFAULT 0,
    compressed_content  TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_session_transcript_compressions_session
    ON session_transcript_compressions(session_id, compression_level, updated_at DESC);

CREATE TABLE chat_turns (
    turn_id               TEXT PRIMARY KEY,
    session_id            TEXT NOT NULL REFERENCES session_store(session_id) ON DELETE CASCADE,
    last_action           TEXT NOT NULL,
    status                TEXT NOT NULL,
    request_payload_json  TEXT NOT NULL,
    user_entry_id         TEXT,
    assistant_entry_id    TEXT,
    latest_trace_id       TEXT,
    resumed_from_trace_id TEXT,
    completion_mode       TEXT,
    last_error_code       INTEGER,
    last_error_message    TEXT,
    attempt_count         INTEGER NOT NULL DEFAULT 0,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL
);

CREATE INDEX idx_chat_turns_session ON chat_turns(session_id, updated_at DESC);

CREATE TABLE conversations (
    id         TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    goal       TEXT NOT NULL,
    summary    TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_conversations_session ON conversations(session_id);

CREATE TABLE messages (
    id                 TEXT PRIMARY KEY,
    conversation_id    TEXT NOT NULL REFERENCES conversations(id),
    role               TEXT NOT NULL,
    content            TEXT NOT NULL,
    compressed_content TEXT,
    compression_level  INTEGER NOT NULL DEFAULT 0,
    is_pinned          INTEGER NOT NULL DEFAULT 0,
    tool_call_json     TEXT,
    token_count        INTEGER DEFAULT 0,
    created_at         TEXT NOT NULL
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);
CREATE INDEX idx_messages_pinned ON messages(conversation_id) WHERE is_pinned = 1;

CREATE VIRTUAL TABLE messages_fts USING fts5(
    content,
    content='messages',
    content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER messages_fts_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE TRIGGER messages_fts_ad AFTER DELETE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content) VALUES('delete', old.rowid, old.content);
END;

CREATE TABLE message_attachments (
    id         TEXT PRIMARY KEY,
    entry_id   TEXT,
    session_id TEXT NOT NULL,
    file_name  TEXT NOT NULL,
    file_path  TEXT NOT NULL,
    file_size  INTEGER NOT NULL DEFAULT 0,
    mime_type  TEXT NOT NULL DEFAULT '',
    url        TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_message_attachments_created ON message_attachments(created_at DESC);
CREATE INDEX idx_message_attachments_entry ON message_attachments(entry_id);
CREATE INDEX idx_message_attachments_session ON message_attachments(session_id);

CREATE TABLE message_feedback (
    id         TEXT PRIMARY KEY,
    entry_id   TEXT NOT NULL,
    session_id TEXT NOT NULL,
    type       TEXT NOT NULL CHECK (type IN ('like', 'dislike')),
    feedback   TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_message_feedback_entry ON message_feedback(entry_id);
CREATE INDEX idx_message_feedback_session ON message_feedback(session_id);

CREATE TABLE context_reports (
    id                   TEXT PRIMARY KEY,
    session_id           TEXT NOT NULL,
    trace_id             TEXT,
    system_prompt_tokens INTEGER NOT NULL DEFAULT 0,
    transcript_tokens    INTEGER NOT NULL DEFAULT 0,
    memory_tokens        INTEGER NOT NULL DEFAULT 0,
    artifact_tokens      INTEGER NOT NULL DEFAULT 0,
    tool_schema_tokens   INTEGER NOT NULL DEFAULT 0,
    tool_result_tokens   INTEGER NOT NULL DEFAULT 0,
    pruning_applied      INTEGER NOT NULL DEFAULT 0,
    compaction_applied   INTEGER NOT NULL DEFAULT 0,
    context_window       INTEGER NOT NULL DEFAULT 0,
    reserved_tokens      INTEGER NOT NULL DEFAULT 0,
    payload_json         TEXT NOT NULL DEFAULT '{}',
    created_at           TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_context_reports_session_id
    ON context_reports(session_id, created_at DESC);
CREATE INDEX idx_context_reports_trace_id
    ON context_reports(trace_id);

CREATE TABLE session_artifacts (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    source_entry_id TEXT,
    trace_id        TEXT,
    artifact_type   TEXT NOT NULL,
    title           TEXT,
    summary         TEXT,
    payload_json    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE,
    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL
);

CREATE INDEX idx_session_artifacts_session_id
    ON session_artifacts(session_id);
CREATE INDEX idx_session_artifacts_source_entry_id
    ON session_artifacts(source_entry_id);
CREATE INDEX idx_session_artifacts_trace_id
    ON session_artifacts(trace_id);

CREATE TABLE session_workspace_items (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    kind            TEXT NOT NULL,
    title           TEXT NOT NULL,
    summary         TEXT NOT NULL,
    payload_json    TEXT,
    status          TEXT NOT NULL,
    priority        INTEGER NOT NULL DEFAULT 0,
    task_id         TEXT,
    source_trace_id TEXT,
    expires_at      TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX idx_workspace_expires_at
    ON session_workspace_items(expires_at)
    WHERE expires_at IS NOT NULL;
CREATE INDEX idx_workspace_session_status
    ON session_workspace_items(session_id, status, updated_at DESC);
CREATE INDEX idx_workspace_task
    ON session_workspace_items(session_id, task_id)
    WHERE task_id IS NOT NULL;

-- ============================================================
-- 二、记忆系统（4 层记忆 + 记忆空间 + 实体/关系图谱）
-- ============================================================

-- --- 记忆空间 ---

CREATE TABLE memory_spaces (
    id            TEXT PRIMARY KEY,
    space_key     TEXT NOT NULL UNIQUE,
    space_type    TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    owner_type    TEXT,
    owner_id      TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE INDEX idx_memory_spaces_type ON memory_spaces(space_type);
CREATE INDEX idx_memory_spaces_owner ON memory_spaces(owner_type, owner_id);

CREATE TABLE memory_space_knowledge_bases (
    memory_space_id   TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    PRIMARY KEY (memory_space_id, knowledge_base_id),
    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE TABLE memory_space_datastores (
    memory_space_id TEXT NOT NULL,
    datastore_id    TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    PRIMARY KEY (memory_space_id, datastore_id),
    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id) ON DELETE CASCADE,
    FOREIGN KEY (datastore_id) REFERENCES ds_collections(id) ON DELETE CASCADE
);

CREATE TABLE chat_turn_memory_snapshots (
    turn_id                          TEXT PRIMARY KEY,
    session_id                       TEXT NOT NULL,
    personal_space_id                TEXT,
    experience_space_id              TEXT,
    domain_write_space_id            TEXT,
    read_space_ids_json              TEXT NOT NULL DEFAULT '[]',
    effective_knowledge_base_ids_json TEXT NOT NULL DEFAULT '[]',
    effective_datastore_ids_json     TEXT NOT NULL DEFAULT '[]',
    personal_learning_enabled        INTEGER NOT NULL DEFAULT 1,
    domain_learning_enabled          INTEGER NOT NULL DEFAULT 0,
    experience_learning_enabled      INTEGER NOT NULL DEFAULT 1,
    resolution_source_json           TEXT NOT NULL DEFAULT '{}',
    created_at                       TEXT NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES chat_turns(turn_id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE,
    FOREIGN KEY (personal_space_id) REFERENCES memory_spaces(id),
    FOREIGN KEY (experience_space_id) REFERENCES memory_spaces(id),
    FOREIGN KEY (domain_write_space_id) REFERENCES memory_spaces(id)
);

CREATE INDEX idx_chat_turn_memory_snapshots_session
    ON chat_turn_memory_snapshots(session_id, created_at DESC);

-- --- 记忆实体与关系图谱 ---

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
    id                       TEXT PRIMARY KEY,
    entity_id                TEXT NOT NULL,
    version_id               TEXT,
    origin_type              TEXT NOT NULL DEFAULT 'UNKNOWN',
    source_reference         TEXT,
    source_conversation_id   TEXT,
    source_session_id        TEXT,
    source_turn_id           TEXT,
    source_entry_id          TEXT,
    source_document_id       TEXT,
    source_knowledge_base_id TEXT,
    source_datastore_id      TEXT,
    source_collection_id     TEXT,
    evidence_excerpt         TEXT,
    evidence_hash            TEXT,
    confidence               REAL NOT NULL DEFAULT 0.0,
    created_at               TEXT NOT NULL,
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
    id              TEXT PRIMARY KEY,
    relation_id     TEXT NOT NULL,
    version_no      INTEGER NOT NULL,
    strength        REAL NOT NULL DEFAULT 0.5,
    properties_json TEXT,
    is_current      INTEGER NOT NULL DEFAULT 1,
    valid_from      TEXT NOT NULL,
    valid_to        TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    FOREIGN KEY (relation_id) REFERENCES memory_relations(id) ON DELETE CASCADE,
    UNIQUE (relation_id, version_no)
);

CREATE UNIQUE INDEX idx_memory_relation_versions_current
    ON memory_relation_versions(relation_id)
    WHERE is_current = 1;

CREATE TABLE memory_relation_provenances (
    id                       TEXT PRIMARY KEY,
    relation_id              TEXT NOT NULL,
    version_id               TEXT,
    origin_type              TEXT NOT NULL DEFAULT 'UNKNOWN',
    source_reference         TEXT,
    source_conversation_id   TEXT,
    source_session_id        TEXT,
    source_turn_id           TEXT,
    source_document_id       TEXT,
    source_knowledge_base_id TEXT,
    source_datastore_id      TEXT,
    source_collection_id     TEXT,
    confidence               REAL NOT NULL DEFAULT 0.0,
    created_at               TEXT NOT NULL,
    FOREIGN KEY (relation_id) REFERENCES memory_relations(id) ON DELETE CASCADE,
    FOREIGN KEY (version_id) REFERENCES memory_relation_versions(id) ON DELETE CASCADE
);

CREATE INDEX idx_memory_relation_provenances_relation
    ON memory_relation_provenances(relation_id, created_at DESC);

CREATE TABLE entity_merge_log (
    id                TEXT PRIMARY KEY,
    primary_entity_id TEXT NOT NULL,
    merged_entity_id  TEXT NOT NULL,
    similarity_score  REAL NOT NULL,
    merge_reason      TEXT,
    created_at        TEXT NOT NULL
);

-- --- 兼容视图：temporal_entities / temporal_relations ---

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

-- --- 其他记忆辅助表 ---

CREATE TABLE memory_consolidation_log (
    id                      TEXT PRIMARY KEY,
    consolidation_type      TEXT NOT NULL,
    conversations_analyzed  INTEGER NOT NULL DEFAULT 0,
    entities_found          INTEGER NOT NULL DEFAULT 0,
    entities_boosted        INTEGER NOT NULL DEFAULT 0,
    extractions_triggered   INTEGER NOT NULL DEFAULT 0,
    templates_created       INTEGER NOT NULL DEFAULT 0,
    templates_updated       INTEGER NOT NULL DEFAULT 0,
    elapsed_ms              INTEGER NOT NULL DEFAULT 0,
    created_at              TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_consolidation_log_type_time ON memory_consolidation_log(consolidation_type, created_at);

CREATE TABLE memory_events (
    id              TEXT PRIMARY KEY,
    event_type      TEXT NOT NULL,
    layer           TEXT NOT NULL,
    session_id      TEXT,
    conversation_id TEXT,
    entity_id       TEXT,
    action          TEXT NOT NULL,
    description     TEXT,
    metadata_json   TEXT NOT NULL DEFAULT '{}',
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_memory_events_session_time ON memory_events(session_id, created_at);
CREATE INDEX idx_memory_events_type_layer ON memory_events(event_type, layer);

CREATE TABLE memory_injection_records (
    id              TEXT PRIMARY KEY,
    source_entry_id TEXT,
    session_id      TEXT NOT NULL,
    entity_ids_json TEXT NOT NULL,
    entity_type     TEXT NOT NULL DEFAULT 'GENERAL',
    source_trace_id TEXT,
    created_at      TEXT NOT NULL,
    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE
);

CREATE INDEX idx_injection_records_session ON memory_injection_records(session_id);
CREATE INDEX idx_injection_records_source_entry ON memory_injection_records(source_entry_id);
CREATE INDEX idx_injection_records_source_trace_type ON memory_injection_records(source_trace_id, entity_type);

CREATE TABLE memory_documents (
    id                TEXT PRIMARY KEY,
    namespace         TEXT NOT NULL,
    doc_type          TEXT NOT NULL,
    title             TEXT NOT NULL,
    path_like_key     TEXT NOT NULL,
    content_markdown  TEXT NOT NULL,
    source_session_id TEXT,
    source_entry_id   TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    FOREIGN KEY (source_session_id) REFERENCES session_store(session_id) ON DELETE SET NULL,
    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL,
    UNIQUE(namespace, path_like_key)
);

CREATE INDEX idx_memory_documents_doc_type ON memory_documents(doc_type);
CREATE INDEX idx_memory_documents_namespace ON memory_documents(namespace);
CREATE INDEX idx_memory_documents_source_session ON memory_documents(source_session_id);

CREATE TABLE memory_document_chunks (
    id             TEXT PRIMARY KEY,
    document_id    TEXT NOT NULL,
    chunk_index    INTEGER NOT NULL,
    content_text   TEXT NOT NULL,
    token_estimate INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES memory_documents(id) ON DELETE CASCADE,
    UNIQUE(document_id, chunk_index)
);

CREATE INDEX idx_memory_document_chunks_document_id
    ON memory_document_chunks(document_id);

CREATE TABLE extraction_event_log (
    id                      TEXT PRIMARY KEY,
    session_id              TEXT NOT NULL,
    operation               TEXT NOT NULL,
    entity_name             TEXT NOT NULL,
    entity_type             TEXT NOT NULL,
    extraction_confidence   REAL,
    importance_score        REAL,
    success                 INTEGER NOT NULL DEFAULT 1,
    error_message           TEXT,
    created_at              TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_extraction_event_log_session ON extraction_event_log(session_id);
CREATE INDEX idx_extraction_event_log_time ON extraction_event_log(created_at);

CREATE TABLE forgetting_log (
    id                  TEXT PRIMARY KEY,
    entity_id           TEXT NOT NULL,
    entity_name         TEXT NOT NULL,
    strategy            TEXT NOT NULL,
    action_taken        TEXT NOT NULL,
    forgetting_priority REAL NOT NULL,
    reason              TEXT,
    compression_summary TEXT,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_forgetting_log_entity ON forgetting_log(entity_id);
CREATE INDEX idx_forgetting_log_time ON forgetting_log(created_at);

CREATE TABLE preference_rules (
    rule_id           TEXT PRIMARY KEY,
    category          TEXT NOT NULL,
    key               TEXT NOT NULL,
    value             TEXT NOT NULL,
    confidence        REAL NOT NULL DEFAULT 0.3,
    learned_from_json TEXT NOT NULL DEFAULT '[]',
    observation_count INTEGER NOT NULL DEFAULT 1,
    created_at        TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(category, key)
);

CREATE TABLE strategy_patterns (
    pattern_id          TEXT PRIMARY KEY,
    situation           TEXT NOT NULL,
    recommended_action  TEXT NOT NULL,
    success_rate        REAL NOT NULL DEFAULT 0.0,
    application_count   INTEGER NOT NULL DEFAULT 0,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE procedure_templates (
    template_id           TEXT PRIMARY KEY,
    name                  TEXT NOT NULL,
    description           TEXT NOT NULL,
    trigger_intent        TEXT NOT NULL,
    steps_json            TEXT NOT NULL,
    variables_json        TEXT NOT NULL DEFAULT '{}',
    success_rate          REAL NOT NULL DEFAULT 0.0,
    use_count             INTEGER NOT NULL DEFAULT 0,
    last_used_at          TEXT,
    source_trace_ids_json TEXT NOT NULL DEFAULT '[]',
    created_at            TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at            TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_procedure_templates_name ON procedure_templates(name);
CREATE INDEX idx_procedure_templates_success ON procedure_templates(success_rate, use_count);

CREATE VIRTUAL TABLE procedure_templates_fts USING fts5(
    name, description, trigger_intent,
    content='procedure_templates',
    content_rowid='rowid',
    tokenize='unicode61'
);

CREATE TABLE working_memory_wal (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    slot_type   TEXT    NOT NULL,
    slot_json   TEXT    NOT NULL,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_wm_wal_session_id ON working_memory_wal(session_id);

CREATE TABLE semantic_cache (
    id                  TEXT PRIMARY KEY,
    query_embedding     BLOB NOT NULL,
    response_text       TEXT NOT NULL,
    scene               TEXT NOT NULL,
    agent_phase         TEXT,
    model_name          TEXT NOT NULL,
    similarity_score    REAL NOT NULL DEFAULT 0.0,
    hit_count           INTEGER NOT NULL DEFAULT 0,
    response_format_key TEXT NOT NULL DEFAULT 'text',
    created_at          TEXT NOT NULL DEFAULT (datetime('now')),
    last_accessed_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_semantic_cache_last_accessed ON semantic_cache(last_accessed_at);
CREATE INDEX idx_semantic_cache_scene_phase ON semantic_cache(scene, agent_phase);
CREATE INDEX idx_semantic_cache_scene_phase_format ON semantic_cache(scene, agent_phase, response_format_key);

-- ============================================================
-- 三、知识库与文档
-- ============================================================

CREATE TABLE knowledge_bases (
    id                   TEXT PRIMARY KEY,
    name                 TEXT NOT NULL,
    description          TEXT NOT NULL DEFAULT '',
    embedding_model      TEXT NOT NULL,
    reranker_model       TEXT,
    chunking_strategy    TEXT NOT NULL DEFAULT 'smart',
    chunking_config_json TEXT NOT NULL DEFAULT '{}',
    document_count       INTEGER NOT NULL DEFAULT 0,
    total_chunks         INTEGER NOT NULL DEFAULT 0,
    tags                 TEXT DEFAULT '[]',
    -- V6: Datastore First 工作区
    system_managed       INTEGER NOT NULL DEFAULT 0,
    owner_datastore_id   TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);

CREATE INDEX idx_knowledge_bases_created_at ON knowledge_bases(created_at DESC);
CREATE INDEX idx_knowledge_bases_name ON knowledge_bases(name);
CREATE INDEX idx_knowledge_bases_tags ON knowledge_bases(tags);
CREATE INDEX idx_knowledge_bases_owner_datastore
    ON knowledge_bases(owner_datastore_id)
    WHERE owner_datastore_id IS NOT NULL;
CREATE INDEX idx_knowledge_bases_system_managed
    ON knowledge_bases(system_managed);

CREATE TABLE session_knowledge_bases (
    session_id        TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,
    PRIMARY KEY (session_id, knowledge_base_id),
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_session_knowledge_bases_kb ON session_knowledge_bases(knowledge_base_id);
CREATE INDEX idx_session_knowledge_bases_session ON session_knowledge_bases(session_id);

CREATE TABLE documents (
    id                   TEXT PRIMARY KEY,
    knowledge_base_id    TEXT NOT NULL,
    file_name            TEXT NOT NULL,
    file_path            TEXT NOT NULL,
    file_size            INTEGER NOT NULL DEFAULT 0,
    mime_type            TEXT NOT NULL DEFAULT '',
    content_hash         TEXT NOT NULL DEFAULT '',
    status               TEXT NOT NULL DEFAULT 'UPLOADING',
    chunk_count          INTEGER NOT NULL DEFAULT 0,
    entity_count         INTEGER NOT NULL DEFAULT 0,
    error_message        TEXT,
    last_processed_stage TEXT,
    metadata_json        TEXT NOT NULL DEFAULT '{}',
    -- V3: 来源感知文档
    source_type          TEXT NOT NULL DEFAULT 'FILE',
    source_key           TEXT NOT NULL DEFAULT '',
    source_datastore_id  TEXT,
    source_collection_id TEXT,
    source_ref_json      TEXT NOT NULL DEFAULT '{}',
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE INDEX idx_documents_kb_id ON documents(knowledge_base_id);
CREATE INDEX idx_documents_content_hash ON documents(knowledge_base_id, content_hash);
CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_kb_source_key ON documents(knowledge_base_id, source_key);
CREATE INDEX idx_documents_kb_source_datastore ON documents(knowledge_base_id, source_datastore_id);
CREATE INDEX idx_documents_source_type ON documents(source_type);

CREATE TABLE document_chunks (
    id                     TEXT PRIMARY KEY,
    document_id            TEXT NOT NULL,
    knowledge_base_id      TEXT NOT NULL,
    content                TEXT NOT NULL,
    context_prefix         TEXT,
    chunk_index            INTEGER NOT NULL DEFAULT 0,
    start_offset           INTEGER NOT NULL DEFAULT 0,
    end_offset             INTEGER NOT NULL DEFAULT 0,
    token_count            INTEGER NOT NULL DEFAULT 0,
    content_hash           TEXT NOT NULL DEFAULT '',
    heading_hierarchy_json TEXT NOT NULL DEFAULT '[]',
    page_number            INTEGER NOT NULL DEFAULT 0,
    metadata_json          TEXT NOT NULL DEFAULT '{}',
    -- V3: 来源感知分块
    source_type            TEXT NOT NULL DEFAULT 'FILE',
    source_datastore_id    TEXT,
    source_collection_id   TEXT,
    -- V23: Parent-Child 分块
    parent_chunk_id        TEXT,
    chunk_level            INTEGER NOT NULL DEFAULT 0,
    created_at             TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE INDEX idx_document_chunks_doc_id ON document_chunks(document_id);
CREATE INDEX idx_document_chunks_hash ON document_chunks(content_hash);
CREATE INDEX idx_document_chunks_kb_id ON document_chunks(knowledge_base_id);
CREATE INDEX idx_document_chunks_source_datastore ON document_chunks(source_datastore_id);
CREATE INDEX idx_document_chunks_source_type ON document_chunks(source_type);
CREATE INDEX idx_document_chunks_parent ON document_chunks(parent_chunk_id);
CREATE INDEX idx_document_chunks_level ON document_chunks(chunk_level);

-- FTS5 使用 trigram tokenizer（V22 最终结构），天然支持 CJK 子串匹配
CREATE VIRTUAL TABLE document_chunks_fts USING fts5(
    content,
    knowledge_base_id UNINDEXED,
    document_id UNINDEXED,
    chunk_id UNINDEXED,
    content=document_chunks,
    content_rowid=rowid,
    tokenize='trigram'
);

CREATE TRIGGER document_chunks_ai AFTER INSERT ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES (new.rowid, new.content, new.knowledge_base_id, new.document_id, new.id);
END;

CREATE TRIGGER document_chunks_ad AFTER DELETE ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(document_chunks_fts, rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES ('delete', old.rowid, old.content, old.knowledge_base_id, old.document_id, old.id);
END;

CREATE TABLE knowledge_base_datastores (
    knowledge_base_id TEXT NOT NULL,
    datastore_id      TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    PRIMARY KEY (knowledge_base_id, datastore_id),
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    FOREIGN KEY (datastore_id) REFERENCES ds_collections(id) ON DELETE CASCADE
);

CREATE INDEX idx_kb_datastores_datastore
    ON knowledge_base_datastores(datastore_id);

CREATE TABLE knowledge_sync_jobs (
    id                TEXT PRIMARY KEY,
    job_type          TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,
    datastore_id      TEXT NOT NULL,
    source_key        TEXT,
    source_version    TEXT,
    payload_json      TEXT NOT NULL DEFAULT '{}',
    status            TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count     INTEGER NOT NULL DEFAULT 0,
    last_error        TEXT,
    available_at      TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    FOREIGN KEY (datastore_id) REFERENCES ds_collections(id) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_sync_jobs_available
    ON knowledge_sync_jobs(status, available_at, created_at);
CREATE INDEX idx_knowledge_sync_jobs_kb_datastore
    ON knowledge_sync_jobs(knowledge_base_id, datastore_id);

CREATE TABLE retrieval_event_log (
    id              TEXT PRIMARY KEY,
    query           TEXT NOT NULL,
    top_k           INTEGER NOT NULL,
    vector_count    INTEGER NOT NULL DEFAULT 0,
    fts_count       INTEGER NOT NULL DEFAULT 0,
    graph_count     INTEGER NOT NULL DEFAULT 0,
    fused_count     INTEGER NOT NULL DEFAULT 0,
    final_count     INTEGER NOT NULL DEFAULT 0,
    top_fused_score REAL NOT NULL DEFAULT 0.0,
    duration_ms     INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_retrieval_event_log_time ON retrieval_event_log(created_at);

-- ============================================================
-- 四、Datastore（结构化数据存储）
-- ============================================================

CREATE TABLE ds_collections (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    type            TEXT NOT NULL DEFAULT 'DOCUMENT',
    properties_json TEXT,
    metadata_json   TEXT,
    created_by      TEXT,
    -- V3: 投影配置
    projection_config_json TEXT NOT NULL DEFAULT '{}',
    -- V6: 默认知识库指针
    default_knowledge_base_id TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE ds_documents (
    id                    TEXT PRIMARY KEY,
    collection_id         TEXT NOT NULL REFERENCES ds_collections(id) ON DELETE CASCADE,
    data_json             TEXT NOT NULL DEFAULT '{}',
    recorded_at           TEXT,
    -- V24: 文件元数据支持
    source_type           TEXT NOT NULL DEFAULT 'DATA',
    knowledge_document_id TEXT,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL
);

CREATE INDEX idx_ds_documents_collection ON ds_documents(collection_id);
CREATE INDEX idx_ds_documents_recorded_at ON ds_documents(collection_id, recorded_at);
CREATE INDEX idx_ds_documents_source_type ON ds_documents(source_type);

CREATE VIRTUAL TABLE ds_documents_fts USING fts5(
    document_id, content,
    tokenize='unicode61'
);

CREATE TABLE session_datastores (
    session_id    TEXT NOT NULL,
    datastore_id  TEXT NOT NULL,
    PRIMARY KEY (session_id, datastore_id),
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE,
    FOREIGN KEY (datastore_id) REFERENCES ds_collections(id) ON DELETE CASCADE
);

CREATE INDEX idx_session_datastores_session ON session_datastores(session_id);
CREATE INDEX idx_session_datastores_datastore ON session_datastores(datastore_id);

-- ============================================================
-- 五、模型服务与路由
-- ============================================================

CREATE TABLE model_services (
    id                            TEXT PRIMARY KEY,
    kind                          TEXT NOT NULL CHECK (kind IN ('GENERATION', 'EMBEDDING', 'RERANK')),
    provider_type                 TEXT NOT NULL,
    api_url                       TEXT NOT NULL,
    api_key                       TEXT,
    model_name                    TEXT NOT NULL,
    timeout_seconds               INTEGER NOT NULL DEFAULT 30,
    priority                      INTEGER NOT NULL DEFAULT 0,
    enabled                       INTEGER NOT NULL DEFAULT 1,
    supported_scenes_json         TEXT NOT NULL DEFAULT '[]',
    generation_capabilities_json  TEXT NOT NULL DEFAULT '[]',
    metadata_json                 TEXT NOT NULL DEFAULT '{}',
    display_name                  TEXT,
    description                   TEXT,
    created_at                    TEXT NOT NULL,
    updated_at                    TEXT NOT NULL
);

CREATE INDEX idx_model_services_kind_enabled
    ON model_services(kind, enabled, priority);
CREATE INDEX idx_model_services_model_name ON model_services(model_name);

CREATE TABLE generation_settings (
    id                           TEXT PRIMARY KEY,
    default_service_id           TEXT,
    scene_service_bindings_json  TEXT NOT NULL DEFAULT '{}',
    created_at                   TEXT NOT NULL,
    updated_at                   TEXT NOT NULL,
    FOREIGN KEY (default_service_id) REFERENCES model_services(id) ON DELETE SET NULL
);

CREATE TABLE embedding_settings (
    id                          TEXT PRIMARY KEY,
    default_service_id          TEXT,
    knowledge_base_service_id   TEXT,
    memory_service_id           TEXT,
    created_at                  TEXT NOT NULL,
    updated_at                  TEXT NOT NULL,
    FOREIGN KEY (default_service_id) REFERENCES model_services(id) ON DELETE SET NULL,
    FOREIGN KEY (knowledge_base_service_id) REFERENCES model_services(id) ON DELETE SET NULL,
    FOREIGN KEY (memory_service_id) REFERENCES model_services(id) ON DELETE SET NULL
);

CREATE TABLE rerank_settings (
    id                  TEXT PRIMARY KEY,
    enabled             INTEGER NOT NULL DEFAULT 0,
    mode                TEXT NOT NULL DEFAULT 'DISABLED'
                            CHECK (mode IN ('DISABLED', 'NATIVE', 'LLM_POINTWISE', 'LLM_LISTWISE')),
    native_service_id   TEXT,
    llm_service_id      TEXT,
    knowledge_top_k     INTEGER NOT NULL DEFAULT 5,
    memory_enabled      INTEGER NOT NULL DEFAULT 1,
    memory_top_k        INTEGER NOT NULL DEFAULT 10,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    FOREIGN KEY (native_service_id) REFERENCES model_services(id) ON DELETE SET NULL,
    FOREIGN KEY (llm_service_id) REFERENCES model_services(id) ON DELETE SET NULL
);

CREATE TABLE circuit_breaker_states (
    provider_capability TEXT PRIMARY KEY,
    state               TEXT NOT NULL DEFAULT 'CLOSED'
                         CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    failure_count       INTEGER NOT NULL DEFAULT 0,
    last_failure_at     TEXT,
    state_changed_at    TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

-- --- 供应商模板与模型预置（V10） ---

CREATE TABLE model_service_vendor_templates (
    vendor_key                            TEXT PRIMARY KEY,
    display_name                          TEXT NOT NULL,
    provider_type                         TEXT NOT NULL,
    description                           TEXT NOT NULL,
    default_api_url                       TEXT NOT NULL,
    supported_kinds_json                  TEXT NOT NULL DEFAULT '[]',
    default_timeout_seconds               INTEGER NOT NULL DEFAULT 60,
    default_generation_capabilities_json  TEXT NOT NULL DEFAULT '[]',
    default_generation_scenes_json        TEXT NOT NULL DEFAULT '[]',
    default_supports_streaming            INTEGER NOT NULL DEFAULT 0,
    default_max_context_window            INTEGER,
    sort_order                            INTEGER NOT NULL DEFAULT 0,
    created_at                            TEXT NOT NULL,
    updated_at                            TEXT NOT NULL
);

CREATE TABLE model_service_model_presets (
    vendor_key            TEXT NOT NULL,
    kind                  TEXT NOT NULL CHECK (kind IN ('GENERATION', 'EMBEDDING', 'RERANK')),
    model_value           TEXT NOT NULL,
    label                 TEXT NOT NULL,
    recommended           INTEGER NOT NULL DEFAULT 0,
    capabilities_json     TEXT NOT NULL DEFAULT '[]',
    scenes_json           TEXT NOT NULL DEFAULT '[]',
    supports_streaming    INTEGER NOT NULL DEFAULT 0,
    max_context_window    INTEGER,
    embedding_dimension   INTEGER,
    sort_order            INTEGER NOT NULL DEFAULT 0,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL,
    PRIMARY KEY (vendor_key, kind, model_value),
    FOREIGN KEY (vendor_key) REFERENCES model_service_vendor_templates(vendor_key) ON DELETE CASCADE
);

-- 供应商模板种子数据
INSERT INTO model_service_vendor_templates (
    vendor_key, display_name, provider_type, description, default_api_url,
    supported_kinds_json, default_timeout_seconds,
    default_generation_capabilities_json, default_generation_scenes_json,
    default_supports_streaming, default_max_context_window, sort_order,
    created_at, updated_at
) VALUES
('openai', 'OpenAI', 'OPENAI_COMPATIBLE',
 '官方 API，预置 GPT-5.4 与 text-embedding-3 系列。',
 'https://api.openai.com/v1',
 '["GENERATION","EMBEDDING"]', 60,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 400000, 10, datetime('now'), datetime('now')),
('anthropic', 'Claude / Anthropic', 'ANTHROPIC',
 '原生 Anthropic 接口，预置 Claude 4.6 系列。',
 'https://api.anthropic.com',
 '["GENERATION"]', 60,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 200000, 20, datetime('now'), datetime('now')),
('deepseek', 'DeepSeek', 'OPENAI_COMPATIBLE',
 'OpenAI 兼容 API，预置 deepseek-chat 与 deepseek-reasoner。',
 'https://api.deepseek.com/v1',
 '["GENERATION"]', 60,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 128000, 30, datetime('now'), datetime('now')),
('qwen', '阿里云百炼 / Qwen', 'OPENAI_COMPATIBLE',
 'DashScope OpenAI 兼容接口，预置 Qwen 文本、视觉、向量与排序模型。',
 'https://dashscope.aliyuncs.com/compatible-mode/v1',
 '["GENERATION","EMBEDDING","RERANK"]', 60,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 131072, 40, datetime('now'), datetime('now')),
('custom-openai', '自定义 OpenAI 兼容', 'OPENAI_COMPATIBLE',
 '适合代理网关、自建兼容层或其他 OpenAI 风格接口。',
 'https://api.example.com/v1',
 '["GENERATION","EMBEDDING","RERANK"]', 60,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 131072, 50, datetime('now'), datetime('now')),
('ollama', 'Ollama（本地）', 'OLLAMA',
 '本地模型服务，模型名称按你本机实际 tag 填写。',
 'http://localhost:11434',
 '["GENERATION","EMBEDDING"]', 120,
 '["CHAT","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 32768, 60, datetime('now'), datetime('now')),
('tei', 'TEI / 本地推理', 'TEI',
 '本地 Embedding 或 Rerank 推理服务，模型名称按部署服务填写。',
 'http://localhost:8080',
 '["EMBEDDING","RERANK"]', 60,
 '[]', '[]',
 0, NULL, 70, datetime('now'), datetime('now'));

-- 模型预置种子数据
INSERT INTO model_service_model_presets (
    vendor_key, kind, model_value, label, recommended,
    capabilities_json, scenes_json, supports_streaming,
    max_context_window, embedding_dimension, sort_order,
    created_at, updated_at
) VALUES
('openai', 'GENERATION', 'gpt-5.4', 'GPT-5.4（推荐）', 1,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 400000, NULL, 10, datetime('now'), datetime('now')),
('openai', 'GENERATION', 'gpt-5.4-mini', 'GPT-5.4 Mini', 0,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 400000, NULL, 20, datetime('now'), datetime('now')),
('openai', 'GENERATION', 'gpt-4.1', 'GPT-4.1', 0,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 1048576, NULL, 30, datetime('now'), datetime('now')),
('openai', 'EMBEDDING', 'text-embedding-3-large', 'text-embedding-3-large（推荐）', 1,
 '[]', '[]', 0, NULL, 3072, 40, datetime('now'), datetime('now')),
('openai', 'EMBEDDING', 'text-embedding-3-small', 'text-embedding-3-small', 0,
 '[]', '[]', 0, NULL, 1536, 50, datetime('now'), datetime('now')),
('anthropic', 'GENERATION', 'claude-sonnet-4-6', 'Claude Sonnet 4.6（推荐）', 1,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 200000, NULL, 10, datetime('now'), datetime('now')),
('anthropic', 'GENERATION', 'claude-opus-4-6', 'Claude Opus 4.6', 0,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 200000, NULL, 20, datetime('now'), datetime('now')),
('anthropic', 'GENERATION', 'claude-haiku-4-5', 'Claude Haiku 4.5', 0,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 200000, NULL, 30, datetime('now'), datetime('now')),
('deepseek', 'GENERATION', 'deepseek-chat', 'DeepSeek Chat（推荐）', 1,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 128000, NULL, 10, datetime('now'), datetime('now')),
('deepseek', 'GENERATION', 'deepseek-reasoner', 'DeepSeek Reasoner', 0,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 128000, NULL, 20, datetime('now'), datetime('now')),
('qwen', 'GENERATION', 'qwen-plus', 'Qwen-Plus（推荐）', 1,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 131072, NULL, 10, datetime('now'), datetime('now')),
('qwen', 'GENERATION', 'qwen-flash', 'Qwen-Flash', 0,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 131072, NULL, 20, datetime('now'), datetime('now')),
('qwen', 'GENERATION', 'qwen-vl-max', 'Qwen-VL-Max', 0,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 131072, NULL, 30, datetime('now'), datetime('now')),
('qwen', 'EMBEDDING', 'text-embedding-v4', 'text-embedding-v4（推荐）', 1,
 '[]', '[]', 0, NULL, 1024, 40, datetime('now'), datetime('now')),
('qwen', 'RERANK', 'qwen3-rerank', 'qwen3-rerank（推荐）', 1,
 '[]', '[]', 0, NULL, NULL, 50, datetime('now'), datetime('now')),
('qwen', 'RERANK', 'gte-rerank-v2', 'gte-rerank-v2', 0,
 '[]', '[]', 0, NULL, NULL, 60, datetime('now'), datetime('now'));

-- ============================================================
-- 六、Agent 引擎（Trace / Checkpoint / 沙箱）
-- ============================================================

CREATE TABLE agent_traces (
    id                 TEXT PRIMARY KEY,
    session_id         TEXT NOT NULL,
    user_message       TEXT NOT NULL,
    final_output       TEXT,
    success            INTEGER NOT NULL DEFAULT 1,
    error_message      TEXT,
    termination_reason TEXT,
    total_steps        INTEGER NOT NULL DEFAULT 0,
    total_tokens       INTEGER NOT NULL DEFAULT 0,
    duration_ms        INTEGER NOT NULL DEFAULT 0,
    model_id           TEXT,
    parent_trace_id    TEXT,
    depth              INTEGER NOT NULL DEFAULT 0,
    created_at         TEXT NOT NULL
);

CREATE INDEX idx_agent_traces_session ON agent_traces(session_id, created_at);
CREATE INDEX idx_agent_traces_time ON agent_traces(created_at);

CREATE TABLE agent_trace_steps (
    id              TEXT PRIMARY KEY,
    trace_id        TEXT NOT NULL,
    step_index      INTEGER NOT NULL,
    phase_before    TEXT NOT NULL,
    phase_after     TEXT NOT NULL,
    action_type     TEXT NOT NULL,
    action_json     TEXT NOT NULL,
    tool_id         TEXT,
    tool_input_json TEXT,
    tool_output     TEXT,
    success         INTEGER NOT NULL DEFAULT 1,
    blocked         INTEGER NOT NULL DEFAULT 0,
    block_reason    TEXT,
    tokens_used     INTEGER NOT NULL DEFAULT 0,
    latency_ms      INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    FOREIGN KEY (trace_id) REFERENCES agent_traces(id)
);

CREATE INDEX idx_trace_steps_trace ON agent_trace_steps(trace_id, step_index);

CREATE TABLE agent_checkpoints (
    session_id TEXT NOT NULL,
    channel TEXT NOT NULL,
    task_fingerprint TEXT NOT NULL,
    source_trace_id TEXT NOT NULL,
    state_json TEXT NOT NULL,
    failure_reason TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, channel, task_fingerprint)
);

CREATE INDEX idx_agent_checkpoints_source_trace_id
    ON agent_checkpoints(source_trace_id);
CREATE INDEX idx_agent_checkpoints_updated_at
    ON agent_checkpoints(updated_at);

CREATE TABLE suspended_agents (
    trace_id       TEXT PRIMARY KEY,
    session_id     TEXT NOT NULL,
    channel        TEXT NOT NULL,
    reason_type    TEXT NOT NULL,
    reason_json    TEXT NOT NULL,
    state_json     TEXT NOT NULL,
    budget_json    TEXT,
    stream_id      TEXT,
    suspended_at   TEXT NOT NULL,
    created_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_suspended_agents_reason  ON suspended_agents(reason_type);
CREATE INDEX idx_suspended_agents_session ON suspended_agents(session_id);

CREATE TABLE traces (
    trace_id            TEXT PRIMARY KEY,
    session_id          TEXT NOT NULL,
    goal                TEXT NOT NULL,
    start_time          TEXT NOT NULL,
    end_time            TEXT,
    total_duration_ms   INTEGER,
    total_steps         INTEGER NOT NULL DEFAULT 0,
    total_tokens        INTEGER NOT NULL DEFAULT 0,
    input_tokens        INTEGER NOT NULL DEFAULT 0,
    output_tokens       INTEGER NOT NULL DEFAULT 0,
    success             INTEGER NOT NULL DEFAULT 0,
    termination_reason  TEXT,
    final_output        TEXT,
    error_message       TEXT,
    metadata_json       TEXT,
    created_at          TEXT NOT NULL
);

CREATE INDEX idx_traces_created_at ON traces(created_at);
CREATE INDEX idx_traces_session_id ON traces(session_id);
CREATE INDEX idx_traces_start_time ON traces(start_time);
CREATE INDEX idx_traces_success ON traces(success);

CREATE VIRTUAL TABLE traces_fts USING fts5(
    trace_id, goal, final_output,
    content='traces',
    content_rowid='rowid'
);

CREATE TABLE trace_steps (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id        TEXT NOT NULL REFERENCES traces(trace_id),
    step_index      INTEGER NOT NULL,
    step_type       TEXT NOT NULL,
    timestamp       TEXT NOT NULL,
    duration_ms     INTEGER NOT NULL DEFAULT 0,
    detail_json     TEXT NOT NULL,
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_trace_steps_step_type ON trace_steps(step_type);
CREATE INDEX idx_trace_steps_trace_id ON trace_steps(trace_id);

CREATE TABLE sandbox_executions (
    id                TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL,
    language          TEXT NOT NULL,
    code_hash         TEXT NOT NULL,
    code_length       INTEGER NOT NULL,
    booter_type       TEXT NOT NULL,
    validation_passed INTEGER NOT NULL,
    violation_count   INTEGER NOT NULL DEFAULT 0,
    exit_code         INTEGER,
    stdout_length     INTEGER,
    stderr_length     INTEGER,
    duration_ms       INTEGER,
    state             TEXT NOT NULL,
    error_message     TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE INDEX idx_sandbox_executions_created ON sandbox_executions(created_at);
CREATE INDEX idx_sandbox_executions_session ON sandbox_executions(session_id);
CREATE INDEX idx_sandbox_executions_state ON sandbox_executions(state);

-- ============================================================
-- 七、工作流引擎
-- ============================================================

CREATE TABLE workflow_definitions (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    description     TEXT,
    version         TEXT,
    enabled         INTEGER NOT NULL DEFAULT 1,
    deleted         INTEGER NOT NULL DEFAULT 0,
    definition_yaml TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE workflow_instances (
    id                       TEXT PRIMARY KEY,
    workflow_id              TEXT NOT NULL,
    state                    TEXT NOT NULL,
    input_json               TEXT,
    context_json             TEXT,
    completed_step_ids_json  TEXT NOT NULL DEFAULT '[]',
    pending_approval_step_id TEXT,
    started_at               TEXT,
    completed_at             TEXT,
    failure_reason           TEXT,
    wake_up_at               TEXT,
    blocked_step_id          TEXT,
    blocked_reason           TEXT,
    trace_id                 TEXT,
    created_at               TEXT NOT NULL,
    updated_at               TEXT NOT NULL,
    FOREIGN KEY (workflow_id) REFERENCES workflow_definitions(id)
);

CREATE INDEX idx_workflow_instances_state ON workflow_instances(state);
CREATE INDEX idx_workflow_instances_wake_up_at ON workflow_instances(state, wake_up_at) WHERE wake_up_at IS NOT NULL;
CREATE INDEX idx_workflow_instances_workflow_id ON workflow_instances(workflow_id);

CREATE TABLE workflow_events (
    id          TEXT PRIMARY KEY,
    instance_id TEXT NOT NULL,
    workflow_id TEXT NOT NULL,
    type        TEXT NOT NULL,
    step_id     TEXT,
    data_json   TEXT,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now')),
    FOREIGN KEY (instance_id) REFERENCES workflow_instances(id)
);

CREATE INDEX idx_workflow_events_created_at ON workflow_events(created_at);
CREATE INDEX idx_workflow_events_instance_id ON workflow_events(instance_id);
CREATE INDEX idx_workflow_events_type ON workflow_events(type);

CREATE TABLE workflow_step_logs (
    id            TEXT PRIMARY KEY,
    instance_id   TEXT NOT NULL,
    step_id       TEXT NOT NULL,
    step_type     TEXT NOT NULL,
    state         TEXT NOT NULL,
    attempt       INTEGER NOT NULL DEFAULT 1,
    retry_count   INTEGER NOT NULL DEFAULT 0,
    input_json    TEXT,
    output_json   TEXT,
    error_message TEXT,
    started_at    TEXT,
    completed_at  TEXT,
    duration_ms   INTEGER,
    created_at    TEXT NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES workflow_instances(id)
);

CREATE INDEX idx_workflow_step_logs_instance_id ON workflow_step_logs(instance_id);
CREATE INDEX idx_workflow_step_logs_step_id ON workflow_step_logs(step_id);

CREATE TABLE cron_tasks (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    schedule    TEXT NOT NULL,
    instruction TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE INDEX idx_cron_tasks_status ON cron_tasks(status);

CREATE TABLE cron_task_logs (
    id          TEXT PRIMARY KEY,
    task_id     TEXT NOT NULL REFERENCES cron_tasks(id) ON DELETE CASCADE,
    executed_at TEXT NOT NULL,
    status      TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    tokens_used INTEGER NOT NULL DEFAULT 0,
    summary     TEXT,
    created_at  TEXT NOT NULL
);

CREATE INDEX idx_cron_task_logs_task_id ON cron_task_logs(task_id);

CREATE TABLE scheduled_tasks (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    trigger_type      TEXT NOT NULL CHECK (trigger_type IN ('ONCE', 'CRON')),
    trigger_at        TEXT,
    cron_expr         TEXT,
    action_json       TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    error_message     TEXT,
    last_triggered_at TEXT,
    next_trigger_at   TEXT,
    metadata_json     TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE INDEX idx_scheduled_tasks_metadata ON scheduled_tasks(metadata_json)
    WHERE metadata_json IS NOT NULL;
CREATE INDEX idx_scheduled_tasks_next_trigger ON scheduled_tasks(next_trigger_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_scheduled_tasks_status ON scheduled_tasks(status);

-- ============================================================
-- 八、权限与安全
-- ============================================================

CREATE TABLE permission_decisions (
    id                   TEXT PRIMARY KEY,
    session_id           TEXT,
    trace_id             TEXT,
    workspace_id         TEXT,
    task_id              TEXT,
    user_id              TEXT,
    tool_id              TEXT NOT NULL,
    action_type          TEXT NOT NULL,
    risk_level           TEXT NOT NULL,
    channel              TEXT NOT NULL,
    resource_scope_json  TEXT NOT NULL DEFAULT '{}',
    decision_type        TEXT NOT NULL,
    matched_grant_id     TEXT,
    matched_subject_type TEXT,
    matched_subject_id   TEXT,
    reason               TEXT,
    created_at           TEXT NOT NULL
);

CREATE INDEX idx_permission_decisions_session
    ON permission_decisions(session_id, created_at);
CREATE INDEX idx_permission_decisions_trace
    ON permission_decisions(trace_id, created_at);
CREATE INDEX idx_permission_decisions_task
    ON permission_decisions(task_id, created_at);

CREATE TABLE execution_grants (
    id                 TEXT PRIMARY KEY,
    subject_type       TEXT NOT NULL,
    subject_id         TEXT NOT NULL,
    action_type        TEXT NOT NULL,
    risk_ceiling       TEXT NOT NULL,
    scope_json         TEXT NOT NULL DEFAULT '{}',
    channels_json      TEXT NOT NULL DEFAULT '[]',
    autonomous_allowed INTEGER NOT NULL DEFAULT 0,
    expires_at         TEXT,
    revoked_at         TEXT,
    revoked_by         TEXT,
    revoked_reason     TEXT,
    created_by         TEXT,
    source_entry_id    TEXT,
    reason             TEXT,
    metadata_json      TEXT NOT NULL DEFAULT '{}',
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);

CREATE INDEX idx_execution_grants_subject
    ON execution_grants(subject_type, subject_id);
CREATE INDEX idx_execution_grants_action
    ON execution_grants(action_type);
CREATE INDEX idx_execution_grants_active
    ON execution_grants(action_type, revoked_at, expires_at);

CREATE TABLE guardrail_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id        TEXT,
    tool_id         TEXT,
    policy_id       TEXT NOT NULL,
    result_type     TEXT NOT NULL,
    reason          TEXT,
    risk_level      TEXT,
    approval_mode   TEXT,
    user_decision   TEXT,
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_guardrail_logs_created_at ON guardrail_logs(created_at);
CREATE INDEX idx_guardrail_logs_policy_id ON guardrail_logs(policy_id);
CREATE INDEX idx_guardrail_logs_trace_id ON guardrail_logs(trace_id);

CREATE TABLE redaction_logs (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id            TEXT,
    context             TEXT,
    applied_rules_json  TEXT,
    created_at          TEXT NOT NULL
);

CREATE INDEX idx_redaction_logs_trace_id ON redaction_logs(trace_id);

-- ============================================================
-- 九、通知系统（V2 简化后最终结构）
-- ============================================================

CREATE TABLE notification_history (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    type_id         TEXT,
    content_json    TEXT NOT NULL,
    channel         TEXT NOT NULL,
    read_status     TEXT NOT NULL DEFAULT 'UNREAD',
    status          TEXT NOT NULL DEFAULT 'SENT',
    metadata_json   TEXT,
    sent_at         TEXT NOT NULL,
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_notification_history_read_status ON notification_history(user_id, read_status);
CREATE INDEX idx_notification_history_sent_at ON notification_history(sent_at);
CREATE INDEX idx_notification_history_user_id ON notification_history(user_id);

-- ============================================================
-- 十、主动提醒系统（V12–V21）
-- ============================================================

CREATE TABLE proactive_reminder_runs (
    id                     TEXT PRIMARY KEY,
    user_id                TEXT NOT NULL,
    started_at             TEXT NOT NULL,
    finished_at            TEXT,
    topics_collected       INTEGER NOT NULL DEFAULT 0,
    decisions_evaluated    INTEGER NOT NULL DEFAULT 0,
    reminders_sent         INTEGER NOT NULL DEFAULT 0,
    context_json           TEXT,
    -- V17: 策略版本绑定
    policy_version_id      TEXT,
    policy_version         INTEGER,
    created_at             TEXT NOT NULL,
    updated_at             TEXT NOT NULL
);

CREATE INDEX idx_proactive_reminder_runs_user_started
    ON proactive_reminder_runs(user_id, started_at DESC);

CREATE TABLE proactive_reminder_decisions (
    id                            TEXT PRIMARY KEY,
    run_id                        TEXT NOT NULL,
    topic_key                     TEXT NOT NULL,
    title                         TEXT NOT NULL,
    signal_id                     TEXT NOT NULL,
    candidate_type                TEXT NOT NULL,
    action                        TEXT NOT NULL,
    decision_reason               TEXT,
    rationale                     TEXT,
    final_score                   REAL NOT NULL,
    evidence_score                REAL NOT NULL,
    timing_score                  REAL NOT NULL,
    urgency_score                 REAL NOT NULL,
    user_fit_score                REAL NOT NULL,
    actionability_score           REAL NOT NULL,
    duplicate_penalty             REAL NOT NULL,
    fatigue_penalty               REAL NOT NULL,
    suggested_at                  TEXT,
    next_evaluation_at            TEXT,
    notified                      INTEGER NOT NULL DEFAULT 0,
    notification_id               TEXT,
    topic_last_reminded_at        TEXT,
    topic_reminders_sent_today    INTEGER NOT NULL DEFAULT 0,
    topic_read_count_30d          INTEGER NOT NULL DEFAULT 0,
    topic_acted_count_30d         INTEGER NOT NULL DEFAULT 0,
    topic_dismissed_count_30d     INTEGER NOT NULL DEFAULT 0,
    topic_snoozed_count_30d       INTEGER NOT NULL DEFAULT 0,
    topic_not_relevant_count_30d  INTEGER NOT NULL DEFAULT 0,
    topic_muted                   INTEGER NOT NULL DEFAULT 0,
    -- V17: 策略版本绑定
    policy_version_id             TEXT,
    policy_version                INTEGER,
    created_at                    TEXT NOT NULL,
    updated_at                    TEXT NOT NULL,
    FOREIGN KEY (run_id) REFERENCES proactive_reminder_runs(id) ON DELETE CASCADE
);

CREATE INDEX idx_proactive_reminder_decisions_run
    ON proactive_reminder_decisions(run_id, created_at ASC);
CREATE INDEX idx_proactive_reminder_decisions_topic
    ON proactive_reminder_decisions(topic_key, created_at DESC);
CREATE INDEX idx_proactive_reminder_decisions_action
    ON proactive_reminder_decisions(action, created_at DESC);
CREATE INDEX idx_proactive_reminder_decisions_action_next_eval
    ON proactive_reminder_decisions(action, next_evaluation_at);
CREATE INDEX idx_proactive_reminder_decisions_policy_version
    ON proactive_reminder_decisions(policy_version_id, created_at DESC);

CREATE TABLE proactive_reminder_evidence (
    id                           TEXT PRIMARY KEY,
    decision_id                  TEXT NOT NULL,
    signal_id                    TEXT NOT NULL,
    signal_kind                  TEXT NOT NULL,
    confidence_score             REAL NOT NULL,
    importance_score             REAL NOT NULL,
    evidence_count               INTEGER NOT NULL,
    observed_at                  TEXT NOT NULL,
    relevant_at                  TEXT,
    preparation_lead_minutes     INTEGER,
    preferred_window_start_hour  INTEGER,
    preferred_window_end_hour    INTEGER,
    anomaly_score                REAL NOT NULL DEFAULT 0,
    actionable                   INTEGER NOT NULL DEFAULT 0,
    resolved                     INTEGER NOT NULL DEFAULT 0,
    summary                      TEXT,
    created_at                   TEXT NOT NULL,
    FOREIGN KEY (decision_id) REFERENCES proactive_reminder_decisions(id) ON DELETE CASCADE
);

CREATE INDEX idx_proactive_reminder_evidence_decision
    ON proactive_reminder_evidence(decision_id, created_at ASC);

CREATE TABLE proactive_reminder_feedback (
    id              TEXT PRIMARY KEY,
    notification_id TEXT NOT NULL UNIQUE,
    user_id         TEXT NOT NULL,
    topic_key       TEXT NOT NULL,
    feedback_type   TEXT NOT NULL,
    comment         TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    FOREIGN KEY (notification_id) REFERENCES notification_history(id) ON DELETE CASCADE
);

CREATE INDEX idx_proactive_reminder_feedback_user_topic
    ON proactive_reminder_feedback(user_id, topic_key, updated_at DESC);
CREATE INDEX idx_proactive_reminder_feedback_type
    ON proactive_reminder_feedback(feedback_type, updated_at DESC);

CREATE TABLE proactive_reminder_topic_preferences (
    user_id    TEXT NOT NULL,
    topic_key  TEXT NOT NULL,
    muted      INTEGER NOT NULL DEFAULT 0,
    muted_at   TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (user_id, topic_key)
);

CREATE INDEX idx_proactive_reminder_topic_preferences_muted
    ON proactive_reminder_topic_preferences(user_id, muted, updated_at DESC);

CREATE TABLE proactive_reminder_policy_traces (
    decision_id                  TEXT PRIMARY KEY,
    base_action                  TEXT NOT NULL,
    opportunity_action           TEXT NOT NULL,
    final_action                 TEXT NOT NULL,
    opportunity_adjusted         INTEGER NOT NULL DEFAULT 0,
    action_adjusted              INTEGER NOT NULL DEFAULT 0,
    training_example_count       INTEGER NOT NULL DEFAULT 0,
    action_feedback_sample_count INTEGER NOT NULL DEFAULT 0,
    trace_json                   TEXT,
    created_at                   TEXT NOT NULL,
    FOREIGN KEY (decision_id) REFERENCES proactive_reminder_decisions(id) ON DELETE CASCADE
);

CREATE INDEX idx_proactive_reminder_policy_traces_final_action
    ON proactive_reminder_policy_traces(final_action, created_at DESC);

CREATE TABLE proactive_reminder_inferred_outcomes (
    id                TEXT PRIMARY KEY,
    decision_id       TEXT NOT NULL,
    notification_id   TEXT,
    user_id           TEXT NOT NULL,
    topic_key         TEXT NOT NULL,
    outcome_type      TEXT NOT NULL,
    evidence_source   TEXT NOT NULL,
    confidence_score  REAL NOT NULL DEFAULT 0,
    evidence_json     TEXT,
    inferred_at       TEXT NOT NULL,
    -- V19: 归因分数
    attribution_score REAL NOT NULL DEFAULT 1.0,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    FOREIGN KEY (decision_id) REFERENCES proactive_reminder_decisions(id) ON DELETE CASCADE,
    FOREIGN KEY (notification_id) REFERENCES notification_history(id) ON DELETE SET NULL,
    UNIQUE (decision_id, outcome_type, evidence_source)
);

CREATE INDEX idx_proactive_reminder_inferred_outcomes_topic
    ON proactive_reminder_inferred_outcomes(user_id, topic_key, inferred_at DESC);
CREATE INDEX idx_proactive_reminder_inferred_outcomes_decision
    ON proactive_reminder_inferred_outcomes(decision_id);

CREATE TABLE proactive_reminder_policy_versions (
    id               TEXT PRIMARY KEY,
    user_id          TEXT NOT NULL,
    version          INTEGER NOT NULL,
    config_signature TEXT NOT NULL,
    config_json      TEXT NOT NULL,
    source           TEXT NOT NULL,
    summary_json     TEXT,
    activated_at     TEXT NOT NULL,
    created_at       TEXT NOT NULL,
    UNIQUE (user_id, version),
    UNIQUE (user_id, config_signature)
);

CREATE INDEX idx_proactive_reminder_policy_versions_user_version
    ON proactive_reminder_policy_versions(user_id, version DESC);
CREATE INDEX idx_proactive_reminder_policy_versions_signature
    ON proactive_reminder_policy_versions(user_id, config_signature);

CREATE TABLE proactive_reminder_replay_reports (
    id                                    TEXT PRIMARY KEY,
    user_id                               TEXT NOT NULL,
    since                                 TEXT NOT NULL,
    generated_at                          TEXT NOT NULL,
    sample_count                          INTEGER NOT NULL DEFAULT 0,
    historical_push_count                 INTEGER NOT NULL DEFAULT 0,
    replayed_push_count                   INTEGER NOT NULL DEFAULT 0,
    suppressed_count                      INTEGER NOT NULL DEFAULT 0,
    promoted_count                        INTEGER NOT NULL DEFAULT 0,
    action_shift_count                    INTEGER NOT NULL DEFAULT 0,
    historical_observed_reward_mean       REAL NOT NULL DEFAULT 0,
    historical_estimated_push_reward_mean REAL NOT NULL DEFAULT 0,
    replayed_estimated_push_reward_mean   REAL NOT NULL DEFAULT 0,
    action_shift_json                     TEXT,
    summary_json                          TEXT,
    created_at                            TEXT NOT NULL
);

CREATE INDEX idx_proactive_reminder_replay_reports_user_generated_at
    ON proactive_reminder_replay_reports(user_id, generated_at DESC);

CREATE TABLE proactive_reminder_topic_aliases (
    user_id             TEXT NOT NULL,
    alias_topic_key     TEXT NOT NULL,
    canonical_topic_key TEXT NOT NULL,
    topic_family        TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    PRIMARY KEY (user_id, alias_topic_key)
);

CREATE INDEX idx_proactive_reminder_topic_aliases_canonical
    ON proactive_reminder_topic_aliases(user_id, canonical_topic_key, updated_at DESC);

CREATE TABLE proactive_reminder_trust_levels (
    user_id              TEXT NOT NULL,
    candidate_type       TEXT NOT NULL,
    trust_level          TEXT NOT NULL DEFAULT 'OBSERVE',
    consecutive_positive INTEGER NOT NULL DEFAULT 0,
    consecutive_negative INTEGER NOT NULL DEFAULT 0,
    cooldown_until       TEXT,
    last_feedback_type   TEXT,
    updated_at           TEXT NOT NULL,
    PRIMARY KEY (user_id, candidate_type)
);

CREATE INDEX idx_proactive_reminder_trust_user_type
    ON proactive_reminder_trust_levels(user_id, candidate_type);

-- ============================================================
-- 十一、渠道适配器（V7）
-- ============================================================

CREATE TABLE channel_plugins (
    plugin_id       TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    version         TEXT NOT NULL,
    vendor          TEXT,
    platform        TEXT NOT NULL,
    connector_mode  TEXT NOT NULL,
    descriptor_json TEXT NOT NULL,
    installed_at    TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX idx_channel_plugins_platform
    ON channel_plugins(platform);

CREATE TABLE channel_instances (
    instance_id         TEXT PRIMARY KEY,
    plugin_id           TEXT NOT NULL,
    platform            TEXT NOT NULL,
    display_name        TEXT NOT NULL,
    enabled             INTEGER NOT NULL DEFAULT 1,
    status              TEXT NOT NULL,
    config_json         TEXT,
    routing_policy_json TEXT,
    last_heartbeat_at   TEXT,
    last_error          TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    FOREIGN KEY (plugin_id) REFERENCES channel_plugins(plugin_id) ON DELETE CASCADE
);

CREATE INDEX idx_channel_instances_plugin_id ON channel_instances(plugin_id);
CREATE INDEX idx_channel_instances_platform ON channel_instances(platform);
CREATE INDEX idx_channel_instances_status ON channel_instances(status);

CREATE TABLE channel_instance_secrets (
    instance_id TEXT PRIMARY KEY,
    secret_json TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES channel_instances(instance_id) ON DELETE CASCADE
);

CREATE TABLE channel_instance_events (
    id           TEXT PRIMARY KEY,
    instance_id  TEXT NOT NULL,
    event_type   TEXT NOT NULL,
    message      TEXT,
    payload_json TEXT,
    created_at   TEXT NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES channel_instances(instance_id) ON DELETE CASCADE
);

CREATE INDEX idx_channel_instance_events_instance_id ON channel_instance_events(instance_id);
CREATE INDEX idx_channel_instance_events_created_at ON channel_instance_events(created_at);

-- ============================================================
-- 十二、技能与插件市场
-- ============================================================

CREATE TABLE skills (
    id                   TEXT PRIMARY KEY,
    name                 TEXT NOT NULL,
    description          TEXT,
    version              TEXT,
    source_type          TEXT NOT NULL,
    source_json          TEXT,
    instructions         TEXT NOT NULL,
    suggested_tools_json TEXT,
    metadata_json        TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);

CREATE INDEX idx_skills_name ON skills(name);
CREATE INDEX idx_skills_source_type ON skills(source_type);

CREATE TABLE skill_audit_logs (
    id                TEXT PRIMARY KEY,
    skill_id          TEXT NOT NULL,
    event_type        TEXT NOT NULL,
    event_detail_json TEXT,
    source_type       TEXT NOT NULL,
    operator          TEXT NOT NULL,
    created_at        TEXT NOT NULL
);

CREATE INDEX idx_skill_audit_logs_created_at ON skill_audit_logs(created_at);
CREATE INDEX idx_skill_audit_logs_skill_id ON skill_audit_logs(skill_id);

CREATE TABLE skill_embedding_cache (
    skill_id     TEXT NOT NULL PRIMARY KEY,
    content_hash TEXT NOT NULL,
    embedding    BLOB NOT NULL,
    created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE installed_extensions (
    id                   TEXT PRIMARY KEY,
    package_id           TEXT NOT NULL UNIQUE,
    type                 TEXT NOT NULL,
    name                 TEXT NOT NULL,
    version              TEXT NOT NULL,
    index_source_url     TEXT NOT NULL,
    repo_url             TEXT NOT NULL,
    file_path            TEXT NOT NULL,
    requirements_json    TEXT,
    security_report_json TEXT,
    -- V8: 安装资源
    install_root_path    TEXT NOT NULL DEFAULT '',
    assets_json          TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);

CREATE INDEX idx_installed_extensions_package_id ON installed_extensions(package_id);
CREATE INDEX idx_installed_extensions_type ON installed_extensions(type);

CREATE TABLE marketplace_index_cache (
    id          TEXT PRIMARY KEY,
    source_url  TEXT NOT NULL UNIQUE,
    index_json  TEXT NOT NULL,
    fetched_at  TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

-- ============================================================
-- 十三、网关与监控
-- ============================================================

CREATE TABLE gateway_sessions (
    session_id      TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    channel_type    TEXT NOT NULL CHECK (channel_type IN ('cli', 'web', 'wecom', 'dingtalk', 'feishu')),
    state           TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'idle', 'expired', 'closed')),
    total_tokens    INTEGER NOT NULL DEFAULT 0,
    total_requests  INTEGER NOT NULL DEFAULT 0,
    metadata_json   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_active_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_gateway_sessions_channel_type ON gateway_sessions(channel_type);
CREATE INDEX idx_gateway_sessions_state ON gateway_sessions(state);
CREATE INDEX idx_gateway_sessions_user_id ON gateway_sessions(user_id);

CREATE TABLE gateway_audit_log (
    audit_id                TEXT PRIMARY KEY,
    message_id              TEXT NOT NULL,
    session_id              TEXT,
    channel_type            TEXT NOT NULL,
    user_id                 TEXT NOT NULL,
    request_content_hash    TEXT,
    request_summary         TEXT,
    response_status_code    INTEGER NOT NULL,
    response_summary        TEXT,
    route_type              TEXT CHECK (route_type IN ('fast_path', 'agent', 'error')),
    latency_ms              INTEGER NOT NULL,
    prompt_tokens           INTEGER NOT NULL DEFAULT 0,
    completion_tokens       INTEGER NOT NULL DEFAULT 0,
    total_tokens            INTEGER NOT NULL DEFAULT 0,
    model_id                TEXT,
    middleware_results_json  TEXT,
    created_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_audit_created_at ON gateway_audit_log(created_at);
CREATE INDEX idx_audit_message_id ON gateway_audit_log(message_id);
CREATE INDEX idx_audit_session_id ON gateway_audit_log(session_id);
CREATE INDEX idx_audit_user_id ON gateway_audit_log(user_id);

CREATE TABLE rate_limit_counters (
    counter_id      TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    channel_type    TEXT NOT NULL,
    counter_type    TEXT NOT NULL CHECK (counter_type IN ('tokens_per_hour', 'tokens_per_day', 'requests_per_minute')),
    window_start    TEXT NOT NULL,
    window_end      TEXT NOT NULL,
    current_count   INTEGER NOT NULL DEFAULT 0,
    max_count       INTEGER NOT NULL,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_rate_limit_user_channel_type ON rate_limit_counters(user_id, channel_type, counter_type);

CREATE TABLE failed_messages (
    id          TEXT PRIMARY KEY,
    channel     TEXT NOT NULL,
    user_id     TEXT NOT NULL,
    response_id TEXT NOT NULL,
    content     TEXT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error       TEXT,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_failed_messages_channel ON failed_messages(channel);
CREATE INDEX idx_failed_messages_created_at ON failed_messages(created_at);

CREATE TABLE daily_token_usage (
    usage_date     TEXT PRIMARY KEY,
    input_tokens   INTEGER NOT NULL DEFAULT 0,
    output_tokens  INTEGER NOT NULL DEFAULT 0,
    total_tokens   INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

CREATE INDEX idx_daily_token_usage_updated_at ON daily_token_usage(updated_at);

CREATE TABLE user_behavior (
    user_id             TEXT PRIMARY KEY,
    total_interactions  INTEGER NOT NULL DEFAULT 0,
    security_incidents  INTEGER NOT NULL DEFAULT 0,
    last_incident_at    TEXT,
    first_seen_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_user_behavior_incidents ON user_behavior(security_incidents);

-- ============================================================
-- 十四、评估与分析
-- ============================================================

CREATE TABLE eval_runs (
    eval_run_id     TEXT PRIMARY KEY,
    metadata_json   TEXT,
    is_baseline     INTEGER DEFAULT 0,
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_eval_runs_baseline ON eval_runs(is_baseline);

CREATE TABLE eval_results (
    eval_id                 TEXT PRIMARY KEY,
    trace_id                TEXT,
    scenario_id             TEXT NOT NULL,
    dimension_scores_json   TEXT NOT NULL,
    overall_score           REAL NOT NULL,
    violations_json         TEXT NOT NULL,
    suggestions_json        TEXT NOT NULL,
    llm_judge_score         REAL,
    llm_judge_justification TEXT,
    llm_judge_tokens_used   INTEGER DEFAULT 0,
    git_commit_hash         TEXT,
    git_branch              TEXT,
    eval_run_id             TEXT NOT NULL,
    evaluated_at            TEXT NOT NULL,
    created_at              TEXT NOT NULL,
    diagnostic_json         TEXT,
    run_metadata_json       TEXT
);

CREATE INDEX idx_eval_results_eval_run_id ON eval_results(eval_run_id);
CREATE INDEX idx_eval_results_evaluated_at ON eval_results(evaluated_at);
CREATE INDEX idx_eval_results_scenario_id ON eval_results(scenario_id);

CREATE TABLE eval_feedback (
    feedback_id     TEXT PRIMARY KEY,
    eval_id         TEXT NOT NULL,
    scenario_id     TEXT NOT NULL,
    feedback_type   TEXT NOT NULL,
    comment         TEXT,
    golden_answer   TEXT,
    created_by      TEXT,
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_eval_feedback_eval_id ON eval_feedback(eval_id);
CREATE INDEX idx_eval_feedback_scenario ON eval_feedback(scenario_id);

CREATE TABLE evaluation_results (
    trace_id                    TEXT PRIMARY KEY,
    evaluated_at                TEXT NOT NULL,
    tool_selection_score        REAL NOT NULL,
    parameter_validity_score    REAL NOT NULL,
    step_efficiency_score       REAL NOT NULL,
    policy_compliance_score     REAL NOT NULL,
    token_efficiency_score      REAL NOT NULL,
    overall_score               REAL NOT NULL,
    actual_steps                INTEGER NOT NULL,
    actual_tokens               INTEGER NOT NULL,
    violations_json             TEXT,
    suggestions_json            TEXT,
    created_at                  TEXT NOT NULL
);

CREATE INDEX idx_evaluation_results_created_at ON evaluation_results(created_at);
CREATE INDEX idx_evaluation_results_overall_score ON evaluation_results(overall_score);

-- ============================================================
-- 十五、用户设置
-- ============================================================

CREATE TABLE user_settings (
    id                      TEXT PRIMARY KEY,
    theme                   TEXT NOT NULL DEFAULT 'system',
    language                TEXT NOT NULL DEFAULT 'zh-CN',
    enable_streaming        INTEGER NOT NULL DEFAULT 1,
    enable_function_call    INTEGER NOT NULL DEFAULT 1,
    enable_knowledge_base   INTEGER NOT NULL DEFAULT 1,
    enable_tool_call        INTEGER NOT NULL DEFAULT 1,
    knowledge_config_json   TEXT DEFAULT '{}',
    channel_config_json     TEXT NOT NULL DEFAULT '{}',
    search_config_json      TEXT NOT NULL DEFAULT '{}',
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

-- ============================================================
-- 十六、种子数据（默认记忆空间）
-- ============================================================

INSERT OR IGNORE INTO memory_spaces (
    id, space_key, space_type, display_name, owner_type, owner_id, metadata_json, created_at, updated_at
) VALUES (
    'memory-space-personal-default',
    'personal:default',
    'PERSONAL',
    '个人记忆',
    'SYSTEM',
    'default',
    '{}',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
);

INSERT OR IGNORE INTO memory_spaces (
    id, space_key, space_type, display_name, owner_type, owner_id, metadata_json, created_at, updated_at
) VALUES (
    'memory-space-experience-default',
    'agent:default',
    'EXPERIENCE',
    'Agent经验',
    'SYSTEM',
    'default',
    '{}',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
);

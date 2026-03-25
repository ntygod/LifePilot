-- ============================================================
-- V1: 初始化脚本
-- 仅保留 transcript-first 时代的最终结构，不再保留历史迁移链。
-- ============================================================

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

CREATE TABLE circuit_breaker_states (
    provider_capability TEXT PRIMARY KEY,
    state               TEXT NOT NULL DEFAULT 'CLOSED'
                         CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    failure_count       INTEGER NOT NULL DEFAULT 0,
    last_failure_at     TEXT,
    state_changed_at    TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

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

CREATE TABLE conversations (
    id         TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    goal       TEXT NOT NULL,
    summary    TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

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

CREATE TABLE cron_tasks (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    schedule    TEXT NOT NULL,
    instruction TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE TABLE daily_token_usage (
    usage_date     TEXT PRIMARY KEY,
    input_tokens   INTEGER NOT NULL DEFAULT 0,
    output_tokens  INTEGER NOT NULL DEFAULT 0,
    total_tokens   INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

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
    created_at             TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE VIRTUAL TABLE document_chunks_fts USING fts5(
    content,
    knowledge_base_id UNINDEXED,
    document_id UNINDEXED,
    chunk_id UNINDEXED,
    content=document_chunks,
    content_rowid=rowid
);

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
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE TABLE ds_collections (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    type            TEXT NOT NULL DEFAULT 'DOCUMENT',
    properties_json TEXT,
    metadata_json   TEXT,
    created_by      TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE ds_documents (
    id            TEXT PRIMARY KEY,
    collection_id TEXT NOT NULL REFERENCES ds_collections(id) ON DELETE CASCADE,
    data_json     TEXT NOT NULL DEFAULT '{}',
    recorded_at   TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE VIRTUAL TABLE ds_documents_fts USING fts5(
    document_id, content,
    tokenize='unicode61'
);

CREATE TABLE entity_merge_log (
    id                TEXT PRIMARY KEY,
    primary_entity_id TEXT NOT NULL,
    merged_entity_id  TEXT NOT NULL,
    similarity_score  REAL NOT NULL,
    merge_reason      TEXT,
    created_at        TEXT NOT NULL
);

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
    created_at              TEXT NOT NULL
,
    diagnostic_json         TEXT,
    run_metadata_json       TEXT);

CREATE TABLE eval_runs (
    eval_run_id     TEXT PRIMARY KEY,
    metadata_json   TEXT,
    is_baseline     INTEGER DEFAULT 0,
    created_at      TEXT NOT NULL
);

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
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);

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
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);

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

CREATE TABLE marketplace_index_cache (
    id          TEXT PRIMARY KEY,
    source_url  TEXT NOT NULL UNIQUE,
    index_json  TEXT NOT NULL,
    fetched_at  TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

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

CREATE VIRTUAL TABLE messages_fts USING fts5(
    content,
    content='messages',
    content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TABLE notification_history (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    type_id         TEXT,
    urgency         TEXT NOT NULL,
    content_json    TEXT NOT NULL,
    channel         TEXT NOT NULL,
    read_status     TEXT NOT NULL DEFAULT 'UNREAD',
    status          TEXT NOT NULL DEFAULT 'SENT',
    metadata_json   TEXT,
    sent_at         TEXT NOT NULL,
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE notification_settings (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    type_id         TEXT NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    channels_json   TEXT NOT NULL DEFAULT '["WEB","WECOM","FEISHU","DINGTALK"]',
    min_urgency     TEXT NOT NULL DEFAULT 'LOW',
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(user_id, type_id)
);

CREATE TABLE passive_notification_queue (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    type_id         TEXT,
    urgency         TEXT NOT NULL,
    content_json    TEXT NOT NULL,
    delivered       INTEGER NOT NULL DEFAULT 0,
    enqueued_at     TEXT NOT NULL,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

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

CREATE VIRTUAL TABLE procedure_templates_fts USING fts5(
    name, description, trigger_intent,
    content='procedure_templates',
    content_rowid='rowid',
    tokenize='unicode61'
);

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

CREATE TABLE redaction_logs (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id            TEXT,
    context             TEXT,
    applied_rules_json  TEXT,
    created_at          TEXT NOT NULL
);

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

CREATE TABLE scheduled_tasks (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    trigger_type      TEXT NOT NULL CHECK (trigger_type IN ('ONCE', 'CRON')),
    trigger_at        TEXT,                -- 一次性任务触发时间（ISO 8601）
    cron_expr         TEXT,                -- 周期性任务 cron 表达式
    action_json       TEXT NOT NULL,        -- TaskAction 序列化 JSON
    status            TEXT NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    error_message     TEXT,
    last_triggered_at TEXT,
    next_trigger_at   TEXT,
    metadata_json     TEXT,                -- 扩展元数据（如 {"scheduleId": "xxx"}）
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

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

CREATE TABLE session_knowledge_bases (
        session_id        TEXT NOT NULL,
        knowledge_base_id TEXT NOT NULL,
        PRIMARY KEY (session_id, knowledge_base_id),
        FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
    );

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

CREATE TABLE session_transcript_compressions (
    entry_id            TEXT PRIMARY KEY,
    session_id          TEXT NOT NULL,
    compression_level   INTEGER NOT NULL DEFAULT 0,
    compressed_content  TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

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

CREATE VIRTUAL TABLE session_transcript_entries_fts USING fts5(
    entry_id UNINDEXED,
    session_id UNINDEXED,
    role UNINDEXED,
    content,
    tokenize='unicode61 remove_diacritics 2'
);

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

CREATE TABLE skill_audit_logs (
    id                TEXT PRIMARY KEY,
    skill_id          TEXT NOT NULL,
    event_type        TEXT NOT NULL,
    event_detail_json TEXT,
    source_type       TEXT NOT NULL,
    operator          TEXT NOT NULL,
    created_at        TEXT NOT NULL
);

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

CREATE TABLE strategy_patterns (
    pattern_id          TEXT PRIMARY KEY,
    situation           TEXT NOT NULL,
    recommended_action  TEXT NOT NULL,
    success_rate        REAL NOT NULL DEFAULT 0.0,
    application_count   INTEGER NOT NULL DEFAULT 0,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

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
    source_conversation_id  TEXT,
    extraction_confidence   REAL DEFAULT 0.0,
    importance_score        REAL DEFAULT 0.5,
    access_count            INTEGER DEFAULT 0,
    last_accessed_at        TEXT,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

CREATE TABLE temporal_relations (
    id                      TEXT PRIMARY KEY,
    source_entity_id        TEXT NOT NULL REFERENCES temporal_entities(id),
    target_entity_id        TEXT NOT NULL REFERENCES temporal_entities(id),
    relation_type           TEXT NOT NULL,
    strength                REAL DEFAULT 0.5,
    properties_json         TEXT,
    valid_from              TEXT NOT NULL,
    valid_to                TEXT,
    source_conversation_id  TEXT,
    created_at              TEXT NOT NULL
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

CREATE VIRTUAL TABLE traces_fts USING fts5(
    trace_id, goal, final_output,
    content='traces',
    content_rowid='rowid'
);

CREATE TABLE user_behavior (
    user_id             TEXT PRIMARY KEY,
    total_interactions  INTEGER NOT NULL DEFAULT 0,
    security_incidents  INTEGER NOT NULL DEFAULT 0,
    last_incident_at    TEXT,
    first_seen_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE user_settings (
    id                      TEXT PRIMARY KEY,
    theme                   TEXT NOT NULL DEFAULT 'system',
    language                TEXT NOT NULL DEFAULT 'zh-CN',
    enable_streaming        INTEGER NOT NULL DEFAULT 1,
    enable_function_call    INTEGER NOT NULL DEFAULT 1,
    enable_knowledge_base   INTEGER NOT NULL DEFAULT 1,
    enable_tool_call        INTEGER NOT NULL DEFAULT 1,
    knowledge_config_json   TEXT DEFAULT '{}',
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
,
    channel_config_json TEXT NOT NULL DEFAULT '{}',
    search_config_json  TEXT NOT NULL DEFAULT '{}');

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

CREATE TABLE working_memory_wal (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    slot_type   TEXT    NOT NULL,
    slot_json   TEXT    NOT NULL,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_checkpoints_source_trace_id
    ON agent_checkpoints(source_trace_id);

CREATE INDEX idx_agent_checkpoints_updated_at
    ON agent_checkpoints(updated_at);

CREATE INDEX idx_agent_traces_session ON agent_traces(session_id, created_at);

CREATE INDEX idx_agent_traces_time ON agent_traces(created_at);

CREATE INDEX idx_audit_created_at ON gateway_audit_log(created_at);

CREATE INDEX idx_audit_message_id ON gateway_audit_log(message_id);

CREATE INDEX idx_audit_session_id ON gateway_audit_log(session_id);

CREATE INDEX idx_audit_user_id ON gateway_audit_log(user_id);

CREATE INDEX idx_consolidation_log_type_time ON memory_consolidation_log(consolidation_type, created_at);

CREATE INDEX idx_context_reports_session_id
    ON context_reports(session_id, created_at DESC);

CREATE INDEX idx_context_reports_trace_id
    ON context_reports(trace_id);

CREATE INDEX idx_conversations_session ON conversations(session_id);

CREATE INDEX idx_cron_task_logs_task_id ON cron_task_logs(task_id);

CREATE INDEX idx_cron_tasks_status ON cron_tasks(status);

CREATE INDEX idx_daily_token_usage_updated_at ON daily_token_usage(updated_at);

CREATE INDEX idx_document_chunks_doc_id ON document_chunks(document_id);

CREATE INDEX idx_document_chunks_hash ON document_chunks(content_hash);

CREATE INDEX idx_document_chunks_kb_id ON document_chunks(knowledge_base_id);

CREATE INDEX idx_documents_content_hash ON documents(knowledge_base_id, content_hash);

CREATE INDEX idx_documents_kb_id ON documents(knowledge_base_id);

CREATE INDEX idx_documents_status ON documents(status);

CREATE INDEX idx_ds_documents_collection ON ds_documents(collection_id);

CREATE INDEX idx_ds_documents_recorded_at ON ds_documents(collection_id, recorded_at);

CREATE INDEX idx_entities_current ON temporal_entities(is_current) WHERE is_current = 1;

CREATE INDEX idx_entities_importance ON temporal_entities(importance_score, access_count) WHERE is_current = 1;

CREATE INDEX idx_entities_name_type ON temporal_entities(name, type);

CREATE INDEX idx_entities_source ON temporal_entities(source_conversation_id) WHERE source_conversation_id IS NOT NULL;

CREATE INDEX idx_entities_type_valid ON temporal_entities(type, valid_from, valid_to);

CREATE INDEX idx_eval_feedback_eval_id ON eval_feedback(eval_id);

CREATE INDEX idx_eval_feedback_scenario ON eval_feedback(scenario_id);

CREATE INDEX idx_eval_results_eval_run_id ON eval_results(eval_run_id);

CREATE INDEX idx_eval_results_evaluated_at ON eval_results(evaluated_at);

CREATE INDEX idx_eval_results_scenario_id ON eval_results(scenario_id);

CREATE INDEX idx_eval_runs_baseline ON eval_runs(is_baseline);

CREATE INDEX idx_evaluation_results_created_at ON evaluation_results(created_at);

CREATE INDEX idx_evaluation_results_overall_score ON evaluation_results(overall_score);

CREATE INDEX idx_extraction_event_log_session ON extraction_event_log(session_id);

CREATE INDEX idx_extraction_event_log_time ON extraction_event_log(created_at);

CREATE INDEX idx_failed_messages_channel ON failed_messages(channel);

CREATE INDEX idx_failed_messages_created_at ON failed_messages(created_at);

CREATE INDEX idx_forgetting_log_entity ON forgetting_log(entity_id);

CREATE INDEX idx_forgetting_log_time ON forgetting_log(created_at);

CREATE INDEX idx_gateway_sessions_channel_type ON gateway_sessions(channel_type);

CREATE INDEX idx_gateway_sessions_state ON gateway_sessions(state);

CREATE INDEX idx_gateway_sessions_user_id ON gateway_sessions(user_id);

CREATE INDEX idx_guardrail_logs_created_at ON guardrail_logs(created_at);

CREATE INDEX idx_guardrail_logs_policy_id ON guardrail_logs(policy_id);

CREATE INDEX idx_guardrail_logs_trace_id ON guardrail_logs(trace_id);

CREATE INDEX idx_injection_records_session ON memory_injection_records(session_id);

CREATE INDEX idx_injection_records_source_entry ON memory_injection_records(source_entry_id);

CREATE INDEX idx_injection_records_source_trace_type ON memory_injection_records(source_trace_id, entity_type);

CREATE INDEX idx_installed_extensions_package_id ON installed_extensions(package_id);

CREATE INDEX idx_installed_extensions_type ON installed_extensions(type);

CREATE INDEX idx_knowledge_bases_created_at ON knowledge_bases(created_at DESC);

CREATE INDEX idx_knowledge_bases_name ON knowledge_bases(name);

CREATE INDEX idx_knowledge_bases_tags ON knowledge_bases(tags);

CREATE INDEX idx_model_services_kind_enabled
    ON model_services(kind, enabled, priority);

CREATE INDEX idx_model_services_model_name ON model_services(model_name);

CREATE INDEX idx_memory_document_chunks_document_id
    ON memory_document_chunks(document_id);

CREATE INDEX idx_memory_documents_doc_type
    ON memory_documents(doc_type);

CREATE INDEX idx_memory_documents_namespace
    ON memory_documents(namespace);

CREATE INDEX idx_memory_documents_source_session
    ON memory_documents(source_session_id);

CREATE INDEX idx_memory_events_session_time ON memory_events(session_id, created_at);

CREATE INDEX idx_memory_events_type_layer ON memory_events(event_type, layer);

CREATE INDEX idx_message_attachments_created ON message_attachments(created_at DESC);

CREATE INDEX idx_message_attachments_entry ON message_attachments(entry_id);

CREATE INDEX idx_message_attachments_session ON message_attachments(session_id);

CREATE INDEX idx_message_feedback_entry ON message_feedback(entry_id);

CREATE INDEX idx_message_feedback_session ON message_feedback(session_id);

CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);

CREATE INDEX idx_messages_pinned ON messages(conversation_id) WHERE is_pinned = 1;

CREATE INDEX idx_notification_history_read_status ON notification_history(user_id, read_status);

CREATE INDEX idx_notification_history_sent_at ON notification_history(sent_at);

CREATE INDEX idx_notification_history_user_id ON notification_history(user_id);

CREATE INDEX idx_passive_queue_delivered ON passive_notification_queue(delivered);

CREATE INDEX idx_passive_queue_user_id ON passive_notification_queue(user_id, delivered);

CREATE INDEX idx_procedure_templates_name ON procedure_templates(name);

CREATE INDEX idx_procedure_templates_success ON procedure_templates(success_rate, use_count);

CREATE INDEX idx_rate_limit_user_channel_type ON rate_limit_counters(user_id, channel_type, counter_type);

CREATE INDEX idx_redaction_logs_trace_id ON redaction_logs(trace_id);

CREATE INDEX idx_relations_source ON temporal_relations(source_entity_id, relation_type);

CREATE INDEX idx_relations_target ON temporal_relations(target_entity_id, relation_type);

CREATE INDEX idx_relations_valid ON temporal_relations(valid_from, valid_to) WHERE valid_to IS NULL;

CREATE INDEX idx_retrieval_event_log_time ON retrieval_event_log(created_at);

CREATE INDEX idx_sandbox_executions_created ON sandbox_executions(created_at);

CREATE INDEX idx_sandbox_executions_session ON sandbox_executions(session_id);

CREATE INDEX idx_sandbox_executions_state ON sandbox_executions(state);

CREATE INDEX idx_scheduled_tasks_metadata ON scheduled_tasks(metadata_json)
    WHERE metadata_json IS NOT NULL;

CREATE INDEX idx_scheduled_tasks_next_trigger ON scheduled_tasks(next_trigger_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_scheduled_tasks_status ON scheduled_tasks(status);

CREATE INDEX idx_semantic_cache_last_accessed ON semantic_cache(last_accessed_at);

CREATE INDEX idx_semantic_cache_scene_phase ON semantic_cache(scene, agent_phase);

CREATE INDEX idx_semantic_cache_scene_phase_format ON semantic_cache(scene, agent_phase, response_format_key);

CREATE INDEX idx_session_artifacts_session_id
    ON session_artifacts(session_id);

CREATE INDEX idx_session_artifacts_source_entry_id
    ON session_artifacts(source_entry_id);

CREATE INDEX idx_session_artifacts_trace_id
    ON session_artifacts(trace_id);

CREATE INDEX idx_session_knowledge_bases_kb ON session_knowledge_bases(knowledge_base_id);

CREATE INDEX idx_session_knowledge_bases_session ON session_knowledge_bases(session_id);

CREATE INDEX idx_session_store_channel
    ON session_store(channel, last_activity_at DESC);

CREATE INDEX idx_session_store_last_activity_at
    ON session_store(last_activity_at DESC);

CREATE INDEX idx_session_store_updated_at
    ON session_store(updated_at DESC);

CREATE INDEX idx_session_transcript_compressions_session
    ON session_transcript_compressions(session_id, compression_level, updated_at DESC);

CREATE INDEX idx_session_transcript_entries_branch
    ON session_transcript_entries(session_id, branch_id, created_at);

CREATE INDEX idx_session_transcript_entries_session_created
    ON session_transcript_entries(session_id, created_at, id);

CREATE INDEX idx_session_transcript_entries_trace
    ON session_transcript_entries(trace_id, created_at);

CREATE INDEX idx_session_transcript_entries_turn
    ON session_transcript_entries(turn_id, created_at);

CREATE INDEX idx_skill_audit_logs_created_at ON skill_audit_logs(created_at);

CREATE INDEX idx_skill_audit_logs_skill_id ON skill_audit_logs(skill_id);

CREATE INDEX idx_skills_name ON skills(name);

CREATE INDEX idx_skills_source_type ON skills(source_type);

CREATE INDEX idx_suspended_agents_reason  ON suspended_agents(reason_type);

CREATE INDEX idx_suspended_agents_session ON suspended_agents(session_id);

CREATE INDEX idx_trace_steps_step_type ON trace_steps(step_type);

CREATE INDEX idx_trace_steps_trace ON agent_trace_steps(trace_id, step_index);

CREATE INDEX idx_trace_steps_trace_id ON trace_steps(trace_id);

CREATE INDEX idx_traces_created_at ON traces(created_at);

CREATE INDEX idx_traces_session_id ON traces(session_id);

CREATE INDEX idx_traces_start_time ON traces(start_time);

CREATE INDEX idx_traces_success ON traces(success);

CREATE INDEX idx_user_behavior_incidents ON user_behavior(security_incidents);

CREATE INDEX idx_wm_wal_session_id ON working_memory_wal(session_id);

CREATE INDEX idx_workflow_events_created_at ON workflow_events(created_at);

CREATE INDEX idx_workflow_events_instance_id ON workflow_events(instance_id);

CREATE INDEX idx_workflow_events_type ON workflow_events(type);

CREATE INDEX idx_workflow_instances_state ON workflow_instances(state);

CREATE INDEX idx_workflow_instances_wake_up_at ON workflow_instances(state, wake_up_at) WHERE wake_up_at IS NOT NULL;

CREATE INDEX idx_workflow_instances_workflow_id ON workflow_instances(workflow_id);

CREATE INDEX idx_workflow_step_logs_instance_id ON workflow_step_logs(instance_id);

CREATE INDEX idx_workflow_step_logs_step_id ON workflow_step_logs(step_id);

CREATE INDEX idx_workspace_expires_at
    ON session_workspace_items(expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX idx_workspace_session_status
    ON session_workspace_items(session_id, status, updated_at DESC);

CREATE INDEX idx_workspace_task
    ON session_workspace_items(session_id, task_id)
    WHERE task_id IS NOT NULL;

CREATE TRIGGER document_chunks_ad AFTER DELETE ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(document_chunks_fts, rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES ('delete', old.rowid, old.content, old.knowledge_base_id, old.document_id, old.id);
END;

CREATE TRIGGER document_chunks_ai AFTER INSERT ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES (new.rowid, new.content, new.knowledge_base_id, new.document_id, new.id);
END;

CREATE TRIGGER messages_fts_ad AFTER DELETE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content) VALUES('delete', old.rowid, old.content);
END;

CREATE TRIGGER messages_fts_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE TRIGGER messages_fts_au AFTER UPDATE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content) VALUES('delete', old.rowid, old.content);
    INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE TRIGGER procedure_templates_fts_ad AFTER DELETE ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(procedure_templates_fts, rowid, name, description, trigger_intent)
        VALUES ('delete', old.rowid, old.name, old.description, old.trigger_intent);
END;

CREATE TRIGGER procedure_templates_fts_ai AFTER INSERT ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(rowid, name, description, trigger_intent)
        VALUES (new.rowid, new.name, new.description, new.trigger_intent);
END;

CREATE TRIGGER procedure_templates_fts_au AFTER UPDATE ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(procedure_templates_fts, rowid, name, description, trigger_intent)
        VALUES ('delete', old.rowid, old.name, old.description, old.trigger_intent);
    INSERT INTO procedure_templates_fts(rowid, name, description, trigger_intent)
        VALUES (new.rowid, new.name, new.description, new.trigger_intent);
END;

CREATE TRIGGER traces_ad AFTER DELETE ON traces BEGIN
    INSERT INTO traces_fts(traces_fts, rowid, trace_id, goal, final_output)
    VALUES ('delete', old.rowid, old.trace_id, old.goal, old.final_output);
END;

CREATE TRIGGER traces_ai AFTER INSERT ON traces BEGIN
    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
END;

CREATE TRIGGER traces_au AFTER UPDATE ON traces BEGIN
    INSERT INTO traces_fts(traces_fts, rowid, trace_id, goal, final_output)
    VALUES ('delete', old.rowid, old.trace_id, old.goal, old.final_output);
    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
END;

CREATE TRIGGER trg_failed_messages_updated_at AFTER UPDATE ON failed_messages
FOR EACH ROW BEGIN
    UPDATE failed_messages SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = NEW.id;
END;

CREATE TRIGGER trg_gateway_sessions_updated_at AFTER UPDATE ON gateway_sessions
FOR EACH ROW BEGIN
    UPDATE gateway_sessions SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE session_id = NEW.session_id;
END;

CREATE TRIGGER trg_rate_limit_counters_updated_at AFTER UPDATE ON rate_limit_counters
FOR EACH ROW BEGIN
    UPDATE rate_limit_counters SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE counter_id = NEW.counter_id;
END;

CREATE TRIGGER trg_session_transcript_entries_fts_ad
AFTER DELETE ON session_transcript_entries
BEGIN
    DELETE FROM session_transcript_entries_fts
    WHERE rowid = old.rowid
      AND old.entry_type IN ('user_message', 'assistant_message')
      AND old.visible_to_user = 1
      AND trim(COALESCE(json_extract(old.payload_json, '$.content'), '')) <> '';
END;

CREATE TRIGGER trg_session_transcript_entries_fts_ai
AFTER INSERT ON session_transcript_entries
BEGIN
    INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
    SELECT new.rowid,
           new.id,
           new.session_id,
           COALESCE(new.role, ''),
           json_extract(new.payload_json, '$.content')
    WHERE new.entry_type IN ('user_message', 'assistant_message')
      AND new.visible_to_user = 1
      AND trim(COALESCE(json_extract(new.payload_json, '$.content'), '')) <> '';
END;

CREATE TRIGGER trg_session_transcript_entries_fts_au
AFTER UPDATE ON session_transcript_entries
BEGIN
    DELETE FROM session_transcript_entries_fts
    WHERE rowid = old.rowid
      AND old.entry_type IN ('user_message', 'assistant_message')
      AND old.visible_to_user = 1
      AND trim(COALESCE(json_extract(old.payload_json, '$.content'), '')) <> '';

    INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
    SELECT new.rowid,
           new.id,
           new.session_id,
           COALESCE(new.role, ''),
           json_extract(new.payload_json, '$.content')
    WHERE new.entry_type IN ('user_message', 'assistant_message')
      AND new.visible_to_user = 1
      AND trim(COALESCE(json_extract(new.payload_json, '$.content'), '')) <> '';
END;

CREATE TRIGGER trg_user_behavior_updated_at AFTER UPDATE ON user_behavior
FOR EACH ROW BEGIN
    UPDATE user_behavior SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE user_id = NEW.user_id;
END;

-- 预置模型服务

INSERT INTO model_services (
    id, kind, provider_type, api_url, api_key, model_name, timeout_seconds, priority, enabled,
    supported_scenes_json, generation_capabilities_json, metadata_json,
    display_name, description, created_at, updated_at
) VALUES
('ollama-qwen2.5', 'GENERATION', 'OLLAMA', 'http://localhost:11434', NULL, 'qwen3:8b', 120, 0, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","memory_compression","proactive_reasoning","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '{"maxContextWindow":131072,"costPerInputToken":0,"costPerOutputToken":0}',
 'Ollama Qwen3 8B', '本地 Ollama Qwen3 8B 生成服务', datetime('now'), datetime('now')),
('deepseek-chat', 'GENERATION', 'DEEPSEEK', 'https://api.deepseek.com', NULL, 'deepseek-v3', 60, 1, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '{"maxContextWindow":131072,"costPerInputToken":2,"costPerOutputToken":8}',
 'DeepSeek V3', 'DeepSeek V3 生成服务', datetime('now'), datetime('now')),
('deepseek-r1', 'GENERATION', 'DEEPSEEK', 'https://api.deepseek.com', NULL, 'deepseek-reasoner', 120, 1, 0,
 '["task_planning","code_generation","agent_reasoning"]',
 '["CHAT","STRUCTURED_OUTPUT","STREAMING"]',
 '{"maxContextWindow":131072,"costPerInputToken":4,"costPerOutputToken":16}',
 'DeepSeek R1', 'DeepSeek R1 推理生成服务', datetime('now'), datetime('now')),
('glm-4', 'GENERATION', 'GLM', 'https://open.bigmodel.cn/api/paas/v4', NULL, 'glm-4-plus', 60, 1, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '{"maxContextWindow":131072,"costPerInputToken":50,"costPerOutputToken":50}',
 '智谱 GLM-4-Plus', '智谱 GLM-4-Plus 生成服务', datetime('now'), datetime('now')),
('qwen-plus', 'GENERATION', 'QWEN', 'https://dashscope.aliyuncs.com/compatible-mode', NULL, 'qwen3-plus', 60, 1, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '{"maxContextWindow":131072,"costPerInputToken":8,"costPerOutputToken":8}',
 '通义千问 3 Plus', '通义千问 3 Plus 生成服务', datetime('now'), datetime('now')),
('wenxin-ernie', 'GENERATION', 'WENXIN', 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat', NULL, 'ernie-4.5-turbo-128k', 60, 1, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '{"maxContextWindow":131072,"costPerInputToken":4,"costPerOutputToken":8}',
 '文心一言 4.5 Turbo', '文心一言 4.5 Turbo 生成服务', datetime('now'), datetime('now')),
('anthropic-claude', 'GENERATION', 'OPENAI_COMPATIBLE', 'https://api.anthropic.com/v1', NULL, 'claude-sonnet-4-6', 60, 2, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '{"maxContextWindow":204800,"costPerInputToken":300,"costPerOutputToken":1500}',
 'Anthropic Claude Sonnet 4.6', 'Claude Sonnet 4.6 生成服务', datetime('now'), datetime('now')),
('openai-gpt-4', 'GENERATION', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', NULL, 'gpt-4.1', 60, 2, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '{"maxContextWindow":1048576,"costPerInputToken":200,"costPerOutputToken":800}',
 'OpenAI GPT-4.1', 'OpenAI GPT-4.1 生成服务', datetime('now'), datetime('now')),
('openai-gpt-4o-mini', 'GENERATION', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', NULL, 'gpt-4o-mini', 30, 2, 0,
 '["chat","knowledge_extraction","memory_compression","knowledge_rerank","document_summary"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
 '{"maxContextWindow":131072,"costPerInputToken":15,"costPerOutputToken":60}',
 'OpenAI GPT-4o Mini', 'OpenAI GPT-4o Mini 生成服务', datetime('now'), datetime('now')),
('ollama-nomic-embed', 'EMBEDDING', 'OLLAMA', 'http://localhost:11434', NULL, 'nomic-embed-text:v1.5', 30, 0, 0,
 '[]', '[]',
 '{"embeddingDimension":1024,"maxContextWindow":8192}',
 'Ollama Nomic Embed', '本地 Ollama Nomic Embed 向量服务', datetime('now'), datetime('now')),
('tei-embedding', 'EMBEDDING', 'TEI', 'http://localhost:8080/v1', NULL, 'bge-base-en-v1.5', 30, 0, 0,
 '[]', '[]',
 '{"embeddingDimension":1024,"maxContextWindow":8192}',
 'TEI Embedding', '本地 TEI 向量服务', datetime('now'), datetime('now'));

INSERT INTO generation_settings (
    id, default_service_id, scene_service_bindings_json, created_at, updated_at
) VALUES (
    'default', 'ollama-qwen2.5', '{}', datetime('now'), datetime('now')
);

INSERT INTO embedding_settings (
    id, default_service_id, knowledge_base_service_id, memory_service_id, created_at, updated_at
) VALUES (
    'default', 'ollama-nomic-embed', 'ollama-nomic-embed', 'ollama-nomic-embed',
    datetime('now'), datetime('now')
);

INSERT INTO rerank_settings (
    id, enabled, mode, native_service_id, llm_service_id, knowledge_top_k, memory_enabled, memory_top_k, created_at, updated_at
) VALUES (
    'default', 0, 'DISABLED', NULL, 'ollama-qwen2.5', 5, 1, 10, datetime('now'), datetime('now')
);

INSERT INTO user_settings (
    id, theme, language,
    enable_streaming, enable_function_call, enable_knowledge_base, enable_tool_call,
    knowledge_config_json,
    created_at, updated_at, channel_config_json, search_config_json
) VALUES (
    'default', 'system', 'zh-CN',
    1, 1, 1, 1, '{}',
    datetime('now'), datetime('now'), '{}', '{}'
);

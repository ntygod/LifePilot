-- =============================================================================
-- V1: ZhiWei 完整初始 Schema
-- 整合自 V1-V62 迭代迁移脚本，反映所有表的最终状态
-- 创建日期：2026-03-15
-- =============================================================================

-- ============================================================
-- 1. LLM 模块
-- ============================================================

-- 熔断器状态持久化表
CREATE TABLE circuit_breaker_states (
    provider_capability TEXT PRIMARY KEY,
    state               TEXT NOT NULL DEFAULT 'CLOSED'
                         CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    failure_count       INTEGER NOT NULL DEFAULT 0,
    last_failure_at     TEXT,
    state_changed_at    TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

-- LLM Provider 配置表
CREATE TABLE llm_providers (
    id                      TEXT PRIMARY KEY,
    type                    TEXT NOT NULL,
    api_url                 TEXT NOT NULL,
    api_key                 TEXT,
    model_name              TEXT NOT NULL,
    timeout_seconds         INTEGER NOT NULL DEFAULT 30,
    priority                INTEGER NOT NULL DEFAULT 0,
    scenes                  TEXT NOT NULL DEFAULT '[]',
    capabilities            TEXT NOT NULL DEFAULT '["CHAT"]',
    enabled                 INTEGER NOT NULL DEFAULT 1,
    cost_per_input_token    INTEGER NOT NULL DEFAULT 0,
    cost_per_output_token   INTEGER NOT NULL DEFAULT 0,
    max_context_window      INTEGER NOT NULL DEFAULT 4096,
    embedding_dimension     INTEGER,
    supports_streaming      INTEGER NOT NULL DEFAULT 0,
    is_preset               INTEGER NOT NULL DEFAULT 0,
    display_name            TEXT,
    description             TEXT,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

CREATE INDEX idx_llm_providers_enabled ON llm_providers(enabled);
CREATE INDEX idx_llm_providers_type ON llm_providers(type);
CREATE INDEX idx_llm_providers_preset ON llm_providers(is_preset);

-- 语义缓存表
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

CREATE INDEX idx_semantic_cache_scene_phase ON semantic_cache(scene, agent_phase);
CREATE INDEX idx_semantic_cache_last_accessed ON semantic_cache(last_accessed_at);
CREATE INDEX idx_semantic_cache_scene_phase_format ON semantic_cache(scene, agent_phase, response_format_key);


-- 预设 LLM Provider 数据
INSERT INTO llm_providers (
    id, type, api_url, api_key, model_name, timeout_seconds, priority,
    scenes, capabilities, enabled, cost_per_input_token, cost_per_output_token,
    max_context_window, embedding_dimension, supports_streaming, is_preset,
    display_name, description, created_at, updated_at
) VALUES
-- 本地模型
('ollama-qwen2.5', 'OLLAMA', 'http://localhost:11434', NULL, 'qwen2.5:7b', 60, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","memory_compression","proactive_reasoning","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 0, 0, 32768, NULL, 1, 1,
 'Ollama Qwen2.5', '本地运行的 Qwen2.5 模型，无需 API Key', datetime('now'), datetime('now')),

('ollama-nomic-embed', 'OLLAMA', 'http://localhost:11434', NULL, 'nomic-embed-text:v1.5', 30, 0,
 '["embedding"]', '["EMBEDDING"]', 0, 0, 0, 8192, 1024, 0, 1,
 'Ollama Nomic Embed', '本地运行的 Nomic Embed 向量模型', datetime('now'), datetime('now')),

-- TEI 本地嵌入
('tei-embedding', 'TEI', 'http://localhost:8080/v1', NULL, 'bge-base-en-v1.5', 30, 0,
 '["embedding"]', '["EMBEDDING"]', 0, 0, 0, 8192, 1024, 0, 1,
 'TEI Embedding (本地)', '本地部署的 Text Embeddings Inference 服务', datetime('now'), datetime('now')),

-- 国内供应商
('deepseek-chat', 'DEEPSEEK', 'https://api.deepseek.com', NULL, 'deepseek-chat', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 14, 28, 16384, NULL, 1, 1,
 'DeepSeek Chat', 'DeepSeek 对话模型，需要配置 API Key', datetime('now'), datetime('now')),

('qwen-plus', 'QWEN', 'https://dashscope.aliyuncs.com/compatible-mode/v1', NULL, 'qwen-plus', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 12, 12, 32768, NULL, 1, 1,
 '通义千问 Plus', '阿里云通义千问增强模型，需要配置 API Key', datetime('now'), datetime('now')),

('glm-4', 'GLM', 'https://open.bigmodel.cn/api/paas/v4', NULL, 'glm-4', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 10, 10, 8192, NULL, 1, 1,
 '智谱 GLM-4', '智谱 AI GLM-4 模型，需要配置 API Key', datetime('now'), datetime('now')),

('wenxin-ernie', 'WENXIN', 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat', NULL, 'ernie-bot-turbo', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STREAMING"]', 0, 12, 12, 8192, NULL, 1, 1,
 '文心一言', '百度文心一言模型，需要配置 API Key', datetime('now'), datetime('now')),

-- 国外供应商
('openai-gpt-4', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', NULL, 'gpt-4', 30, 2,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]', 0, 3000, 6000, 8192, NULL, 1, 1,
 'OpenAI GPT-4', 'OpenAI GPT-4 模型，需要配置 API Key', datetime('now'), datetime('now')),

('anthropic-claude', 'OPENAI_COMPATIBLE', 'https://api.anthropic.com/v1', NULL, 'claude-3-sonnet-20240229', 30, 2,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent_reasoning","agent_tool_calling","agent_generation","knowledge_rerank","document_summary","skill_generation","memory_compression"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 300, 1500, 200000, NULL, 1, 1,
 'Anthropic Claude', 'Anthropic Claude 模型，需要配置 API Key', datetime('now'), datetime('now'));

-- ============================================================
-- 2. Agent 模块
-- ============================================================

-- Agent 会话表
CREATE TABLE agent_sessions (
    id                      TEXT PRIMARY KEY,
    channel_id              TEXT NOT NULL,
    recent_turns_json       TEXT NOT NULL DEFAULT '[]',
    mentioned_entities_json TEXT NOT NULL DEFAULT '[]',
    active_task_context     TEXT,
    last_active_at          TEXT NOT NULL,
    total_turns             INTEGER NOT NULL DEFAULT 0,
    total_tokens_used       INTEGER NOT NULL DEFAULT 0,
    archived                INTEGER NOT NULL DEFAULT 0,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

CREATE INDEX idx_agent_sessions_channel ON agent_sessions(channel_id, last_active_at);
CREATE INDEX idx_agent_sessions_active ON agent_sessions(archived, last_active_at);

-- Agent 轨迹表
CREATE TABLE agent_traces (
    id                TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL,
    user_message      TEXT NOT NULL,
    final_output      TEXT,
    success           INTEGER NOT NULL DEFAULT 1,
    error_message     TEXT,
    termination_reason TEXT,
    total_steps       INTEGER NOT NULL DEFAULT 0,
    total_tokens      INTEGER NOT NULL DEFAULT 0,
    duration_ms       INTEGER NOT NULL DEFAULT 0,
    model_id          TEXT,
    parent_trace_id   TEXT,
    depth             INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES agent_sessions(id)
);

CREATE INDEX idx_agent_traces_session ON agent_traces(session_id, created_at);
CREATE INDEX idx_agent_traces_time ON agent_traces(created_at);

-- Agent 轨迹步骤表
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


-- ============================================================
-- 3. 记忆系统（L1-L4）
-- ============================================================

-- L2 对话记录表
CREATE TABLE conversations (
    id         TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    goal       TEXT NOT NULL,
    summary    TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_conversations_session ON conversations(session_id);

-- L2 消息记录表
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

-- L2 消息 FTS5 全文索引
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

CREATE TRIGGER messages_fts_au AFTER UPDATE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content) VALUES('delete', old.rowid, old.content);
    INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;

-- L3 语义记忆：时序知识图谱节点（无 conversations FK，支持多来源）
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

CREATE INDEX idx_entities_name_type ON temporal_entities(name, type);
CREATE INDEX idx_entities_current ON temporal_entities(is_current) WHERE is_current = 1;
CREATE INDEX idx_entities_type_valid ON temporal_entities(type, valid_from, valid_to);
CREATE INDEX idx_entities_importance ON temporal_entities(importance_score, access_count) WHERE is_current = 1;
CREATE INDEX idx_entities_source ON temporal_entities(source_conversation_id) WHERE source_conversation_id IS NOT NULL;

-- L3 语义记忆：时序知识图谱边（无 conversations FK）
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

CREATE INDEX idx_relations_source ON temporal_relations(source_entity_id, relation_type);
CREATE INDEX idx_relations_target ON temporal_relations(target_entity_id, relation_type);
CREATE INDEX idx_relations_valid ON temporal_relations(valid_from, valid_to) WHERE valid_to IS NULL;

-- L4 程序记忆：操作模板表
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

-- L4 操作模板 FTS5 全文索引
CREATE VIRTUAL TABLE procedure_templates_fts USING fts5(
    name, description, trigger_intent,
    content='procedure_templates',
    content_rowid='rowid',
    tokenize='unicode61'
);

CREATE TRIGGER procedure_templates_fts_ai AFTER INSERT ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(rowid, name, description, trigger_intent)
        VALUES (new.rowid, new.name, new.description, new.trigger_intent);
END;

CREATE TRIGGER procedure_templates_fts_ad AFTER DELETE ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(procedure_templates_fts, rowid, name, description, trigger_intent)
        VALUES ('delete', old.rowid, old.name, old.description, old.trigger_intent);
END;

CREATE TRIGGER procedure_templates_fts_au AFTER UPDATE ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(procedure_templates_fts, rowid, name, description, trigger_intent)
        VALUES ('delete', old.rowid, old.name, old.description, old.trigger_intent);
    INSERT INTO procedure_templates_fts(rowid, name, description, trigger_intent)
        VALUES (new.rowid, new.name, new.description, new.trigger_intent);
END;

-- L4 偏好规则表
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

-- L4 策略模式表
CREATE TABLE strategy_patterns (
    pattern_id          TEXT PRIMARY KEY,
    situation           TEXT NOT NULL,
    recommended_action  TEXT NOT NULL,
    success_rate        REAL NOT NULL DEFAULT 0.0,
    application_count   INTEGER NOT NULL DEFAULT 0,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

-- L1 工作记忆 WAL 表
CREATE TABLE working_memory_wal (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    slot_type   TEXT    NOT NULL,
    slot_json   TEXT    NOT NULL,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_wm_wal_session_id ON working_memory_wal(session_id);

-- 记忆事件审计表
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

-- 记忆注入记录表
CREATE TABLE memory_injection_records (
    id              TEXT PRIMARY KEY,
    message_id      TEXT NOT NULL,
    session_id      TEXT NOT NULL,
    entity_ids_json TEXT NOT NULL,
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_injection_records_message ON memory_injection_records(message_id);

-- 实体去重合并日志表
CREATE TABLE entity_merge_log (
    id                TEXT PRIMARY KEY,
    primary_entity_id TEXT NOT NULL,
    merged_entity_id  TEXT NOT NULL,
    similarity_score  REAL NOT NULL,
    merge_reason      TEXT,
    created_at        TEXT NOT NULL
);

-- 巩固执行日志
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

-- 遗忘操作日志
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

-- 提取事件日志表
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

-- 检索事件日志表
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
-- 4. 知识库模块
-- ============================================================

-- 知识库表
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

CREATE INDEX idx_knowledge_bases_name ON knowledge_bases(name);
CREATE INDEX idx_knowledge_bases_tags ON knowledge_bases(tags);
CREATE INDEX idx_knowledge_bases_created_at ON knowledge_bases(created_at DESC);

-- 文档表
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

CREATE INDEX idx_documents_kb_id ON documents(knowledge_base_id);
CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_content_hash ON documents(knowledge_base_id, content_hash);

-- 文档分块表
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

CREATE INDEX idx_document_chunks_doc_id ON document_chunks(document_id);
CREATE INDEX idx_document_chunks_kb_id ON document_chunks(knowledge_base_id);
CREATE INDEX idx_document_chunks_hash ON document_chunks(content_hash);

-- 文档分块 FTS5 全文索引
CREATE VIRTUAL TABLE document_chunks_fts USING fts5(
    content,
    knowledge_base_id UNINDEXED,
    document_id UNINDEXED,
    chunk_id UNINDEXED,
    content=document_chunks,
    content_rowid=rowid
);

CREATE TRIGGER document_chunks_ai AFTER INSERT ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES (new.rowid, new.content, new.knowledge_base_id, new.document_id, new.id);
END;

CREATE TRIGGER document_chunks_ad AFTER DELETE ON document_chunks BEGIN
    INSERT INTO document_chunks_fts(document_chunks_fts, rowid, content, knowledge_base_id, document_id, chunk_id)
    VALUES ('delete', old.rowid, old.content, old.knowledge_base_id, old.document_id, old.id);
END;

-- ============================================================
-- 5. Skill 模块
-- ============================================================

-- 内置 Skill 业务表：待办事项
CREATE TABLE todos (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    description TEXT,
    priority    TEXT NOT NULL DEFAULT 'MEDIUM',
    status      TEXT NOT NULL DEFAULT 'PENDING',
    due_date    TEXT,
    tags_json   TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- 内置 Skill 业务表：日程
CREATE TABLE schedules (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    start_time  TEXT NOT NULL,
    end_time    TEXT NOT NULL,
    location    TEXT,
    notes       TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE INDEX idx_schedules_time ON schedules(start_time, end_time);

-- 内置 Skill 业务表：习惯
CREATE TABLE habits (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    frequency       TEXT NOT NULL,
    target_time     TEXT,
    current_streak  INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

-- 习惯打卡记录表
CREATE TABLE habit_logs (
    id          TEXT PRIMARY KEY,
    habit_id    TEXT NOT NULL REFERENCES habits(id),
    checked_at  TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE INDEX idx_habit_logs_habit_checked ON habit_logs(habit_id, checked_at);

-- Skill 审计日志表
CREATE TABLE skill_audit_logs (
    id                TEXT PRIMARY KEY,
    skill_id          TEXT NOT NULL,
    event_type        TEXT NOT NULL,
    event_detail_json TEXT,
    source_type       TEXT NOT NULL,
    operator          TEXT NOT NULL,
    created_at        TEXT NOT NULL
);

CREATE INDEX idx_skill_audit_logs_skill_id ON skill_audit_logs(skill_id);
CREATE INDEX idx_skill_audit_logs_created_at ON skill_audit_logs(created_at);

-- Skill 注册信息持久化表
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

CREATE INDEX idx_skills_source_type ON skills(source_type);
CREATE INDEX idx_skills_name ON skills(name);


-- ============================================================
-- 6. 主动推理模块
-- ============================================================

-- 频率状态表（泛化版，支持 typeId + subject_id 组合键）
CREATE TABLE frequency_states (
    notification_type        TEXT NOT NULL,
    subject_id               TEXT NOT NULL DEFAULT '',
    state                    TEXT NOT NULL DEFAULT 'NORMAL',
    consecutive_ignore_count INTEGER NOT NULL DEFAULT 0,
    last_notified_at         TEXT,
    created_at               TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at               TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (notification_type, subject_id)
);

-- 主动通知记录表
CREATE TABLE proactive_notifications (
    id                  TEXT PRIMARY KEY,
    notification_type   TEXT NOT NULL,
    urgency             TEXT NOT NULL,
    content             TEXT NOT NULL,
    channel             TEXT NOT NULL,
    response_status     TEXT NOT NULL DEFAULT 'PENDING',
    sent_at             TEXT NOT NULL,
    responded_at        TEXT,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_proactive_notifications_type ON proactive_notifications(notification_type);
CREATE INDEX idx_proactive_notifications_sent ON proactive_notifications(sent_at);

-- ============================================================
-- 7. Gateway 模块
-- ============================================================

-- 网关会话表
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

CREATE INDEX idx_gateway_sessions_user_id ON gateway_sessions(user_id);
CREATE INDEX idx_gateway_sessions_channel_type ON gateway_sessions(channel_type);
CREATE INDEX idx_gateway_sessions_state ON gateway_sessions(state);

CREATE TRIGGER trg_gateway_sessions_updated_at AFTER UPDATE ON gateway_sessions
FOR EACH ROW BEGIN
    UPDATE gateway_sessions SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE session_id = NEW.session_id;
END;

-- 审计日志表
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

CREATE INDEX idx_audit_message_id ON gateway_audit_log(message_id);
CREATE INDEX idx_audit_session_id ON gateway_audit_log(session_id);
CREATE INDEX idx_audit_user_id ON gateway_audit_log(user_id);
CREATE INDEX idx_audit_created_at ON gateway_audit_log(created_at);

-- 限流计数器表
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

CREATE TRIGGER trg_rate_limit_counters_updated_at AFTER UPDATE ON rate_limit_counters
FOR EACH ROW BEGIN
    UPDATE rate_limit_counters SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE counter_id = NEW.counter_id;
END;

-- 失败消息队列表
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

CREATE TRIGGER trg_failed_messages_updated_at AFTER UPDATE ON failed_messages
FOR EACH ROW BEGIN
    UPDATE failed_messages SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = NEW.id;
END;

-- 用户行为表
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

CREATE TRIGGER trg_user_behavior_updated_at AFTER UPDATE ON user_behavior
FOR EACH ROW BEGIN
    UPDATE user_behavior SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE user_id = NEW.user_id;
END;


-- ============================================================
-- 8. 工作流模块
-- ============================================================

-- 工作流定义表
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

-- 工作流实例表（DAG 版本）
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

CREATE INDEX idx_workflow_instances_workflow_id ON workflow_instances(workflow_id);
CREATE INDEX idx_workflow_instances_state ON workflow_instances(state);
CREATE INDEX idx_workflow_instances_wake_up_at ON workflow_instances(state, wake_up_at) WHERE wake_up_at IS NOT NULL;

-- 工作流步骤日志表
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

-- 工作流审计事件表
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

CREATE INDEX idx_workflow_events_instance_id ON workflow_events(instance_id);
CREATE INDEX idx_workflow_events_type ON workflow_events(type);
CREATE INDEX idx_workflow_events_created_at ON workflow_events(created_at);

-- ============================================================
-- 9. 沙箱模块
-- ============================================================

-- 沙箱执行审计记录表
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

CREATE INDEX idx_sandbox_executions_session ON sandbox_executions(session_id);
CREATE INDEX idx_sandbox_executions_state ON sandbox_executions(state);
CREATE INDEX idx_sandbox_executions_created ON sandbox_executions(created_at);

-- ============================================================
-- 10. 同步模块
-- ============================================================

-- 同步配置表
CREATE TABLE sync_profiles (
    id                      TEXT PRIMARY KEY,
    name                    TEXT NOT NULL,
    connector_type          TEXT NOT NULL,
    connection_params_json  TEXT NOT NULL,
    sync_direction          TEXT NOT NULL DEFAULT 'BIDIRECTIONAL',
    conflict_policy         TEXT NOT NULL DEFAULT 'LAST_WRITE_WINS',
    cron_expression         TEXT NOT NULL DEFAULT '0 */15 * * * *',
    enabled                 INTEGER NOT NULL DEFAULT 1,
    data_type_filter_json   TEXT,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

-- 同步映射表
CREATE TABLE sync_records (
    id                  TEXT PRIMARY KEY,
    profile_id          TEXT NOT NULL REFERENCES sync_profiles(id) ON DELETE CASCADE,
    local_entity_type   TEXT NOT NULL,
    local_entity_id     TEXT NOT NULL,
    remote_entity_id    TEXT NOT NULL,
    etag                TEXT,
    remote_updated_at   TEXT,
    last_sync_at        TEXT NOT NULL,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE INDEX idx_sync_records_profile ON sync_records(profile_id);
CREATE UNIQUE INDEX idx_sync_records_mapping ON sync_records(profile_id, local_entity_type, local_entity_id);

-- 凭证表
CREATE TABLE sync_credentials (
    id              TEXT PRIMARY KEY,
    profile_id      TEXT NOT NULL REFERENCES sync_profiles(id) ON DELETE CASCADE,
    credential_type TEXT NOT NULL,
    encrypted_value TEXT NOT NULL,
    iv              TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX idx_sync_credentials_profile ON sync_credentials(profile_id);

-- 冲突表
CREATE TABLE sync_conflicts (
    id                      TEXT PRIMARY KEY,
    profile_id              TEXT NOT NULL REFERENCES sync_profiles(id) ON DELETE CASCADE,
    local_entity_type       TEXT NOT NULL,
    local_entity_id         TEXT NOT NULL,
    local_snapshot_json     TEXT NOT NULL,
    remote_snapshot_json    TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'UNRESOLVED',
    resolved_at             TEXT,
    created_at              TEXT NOT NULL
);

CREATE INDEX idx_sync_conflicts_profile ON sync_conflicts(profile_id);
CREATE INDEX idx_sync_conflicts_status ON sync_conflicts(status);

-- 同步状态表
CREATE TABLE sync_state (
    id                  TEXT PRIMARY KEY,
    profile_id          TEXT NOT NULL REFERENCES sync_profiles(id) ON DELETE CASCADE,
    sync_token          TEXT,
    last_sync_at        TEXT,
    last_sync_status    TEXT,
    last_error_message  TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_sync_state_profile ON sync_state(profile_id);


-- ============================================================
-- 11. 评估模块
-- ============================================================

-- 评估结果表
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
);

CREATE INDEX idx_eval_results_scenario_id ON eval_results(scenario_id);
CREATE INDEX idx_eval_results_eval_run_id ON eval_results(eval_run_id);
CREATE INDEX idx_eval_results_evaluated_at ON eval_results(evaluated_at);

-- ============================================================
-- 12. 可观测性模块
-- ============================================================

-- 追踪记录表
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

CREATE INDEX idx_traces_session_id ON traces(session_id);
CREATE INDEX idx_traces_start_time ON traces(start_time);
CREATE INDEX idx_traces_success ON traces(success);
CREATE INDEX idx_traces_created_at ON traces(created_at);

-- 追踪步骤详情表
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

CREATE INDEX idx_trace_steps_trace_id ON trace_steps(trace_id);
CREATE INDEX idx_trace_steps_step_type ON trace_steps(step_type);

-- 护栏审计日志表
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

CREATE INDEX idx_guardrail_logs_trace_id ON guardrail_logs(trace_id);
CREATE INDEX idx_guardrail_logs_policy_id ON guardrail_logs(policy_id);
CREATE INDEX idx_guardrail_logs_created_at ON guardrail_logs(created_at);

-- 脱敏审计日志表
CREATE TABLE redaction_logs (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id            TEXT,
    context             TEXT,
    applied_rules_json  TEXT,
    created_at          TEXT NOT NULL
);

CREATE INDEX idx_redaction_logs_trace_id ON redaction_logs(trace_id);

-- 轨迹评估结果表
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

CREATE INDEX idx_evaluation_results_overall_score ON evaluation_results(overall_score);
CREATE INDEX idx_evaluation_results_created_at ON evaluation_results(created_at);

-- 追踪 FTS5 全文搜索
CREATE VIRTUAL TABLE traces_fts USING fts5(
    trace_id, goal, final_output,
    content='traces',
    content_rowid='rowid'
);

CREATE TRIGGER traces_ai AFTER INSERT ON traces BEGIN
    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
END;

CREATE TRIGGER traces_ad AFTER DELETE ON traces BEGIN
    INSERT INTO traces_fts(traces_fts, rowid, trace_id, goal, final_output)
    VALUES ('delete', old.rowid, old.trace_id, old.goal, old.final_output);
END;

CREATE TRIGGER traces_au AFTER UPDATE ON traces BEGIN
    INSERT INTO traces_fts(traces_fts, rowid, trace_id, goal, final_output)
    VALUES ('delete', old.rowid, old.trace_id, old.goal, old.final_output);
    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
END;

-- ============================================================
-- 13. Web UI 交互模块
-- ============================================================

-- 用户设置表
CREATE TABLE user_settings (
    id                      TEXT PRIMARY KEY,
    theme                   TEXT NOT NULL DEFAULT 'system',
    language                TEXT NOT NULL DEFAULT 'zh-CN',
    llm_provider            TEXT NOT NULL DEFAULT 'ollama-qwen2.5',
    enable_streaming        INTEGER NOT NULL DEFAULT 1,
    enable_function_call    INTEGER NOT NULL DEFAULT 1,
    enable_knowledge_base   INTEGER NOT NULL DEFAULT 1,
    enable_tool_call        INTEGER NOT NULL DEFAULT 1,
    scene_providers         TEXT NOT NULL DEFAULT '{}',
    reranker_config_json    TEXT DEFAULT '{}',
    knowledge_config_json   TEXT DEFAULT '{}',
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

INSERT INTO user_settings (
    id, theme, language, llm_provider,
    enable_streaming, enable_function_call, enable_knowledge_base, enable_tool_call,
    scene_providers, reranker_config_json, knowledge_config_json,
    created_at, updated_at
) VALUES (
    'default', 'system', 'zh-CN', 'ollama-qwen2.5',
    1, 1, 1, 1, '{}', '{}', '{}',
    datetime('now'), datetime('now')
);

-- 会话管理表
CREATE TABLE chat_sessions (
    id              TEXT PRIMARY KEY,
    title           TEXT NOT NULL DEFAULT '新对话',
    summary         TEXT,
    message_count   INTEGER NOT NULL DEFAULT 0,
    is_pinned       INTEGER NOT NULL DEFAULT 0,
    archived        INTEGER NOT NULL DEFAULT 0,
    config_json     TEXT DEFAULT '{}',
    last_message_at TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX idx_chat_sessions_last_message ON chat_sessions(last_message_at DESC);
CREATE INDEX idx_chat_sessions_pinned ON chat_sessions(is_pinned DESC, last_message_at DESC);
CREATE INDEX idx_chat_sessions_updated ON chat_sessions(updated_at DESC);
CREATE INDEX idx_chat_sessions_archived ON chat_sessions(archived, last_message_at DESC);

-- 对话历史消息表（独立于记忆系统）
CREATE TABLE chat_messages (
    id                    TEXT PRIMARY KEY,
    session_id            TEXT NOT NULL,
    role                  TEXT NOT NULL,
    content               TEXT NOT NULL,
    reasoning_summary     TEXT,
    trace_id              TEXT,
    a2ui_components_json  TEXT,
    created_at            TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_messages_session_created ON chat_messages(session_id, created_at);
CREATE INDEX idx_chat_messages_session_role ON chat_messages(session_id, role, created_at);

-- 会话-知识库关联表
CREATE TABLE session_knowledge_bases (
    session_id        TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,
    PRIMARY KEY (session_id, knowledge_base_id),
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_session_knowledge_bases_session ON session_knowledge_bases(session_id);
CREATE INDEX idx_session_knowledge_bases_kb ON session_knowledge_bases(knowledge_base_id);

-- 消息反馈表（FK 指向 chat_messages）
CREATE TABLE message_feedback (
    id         TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    type       TEXT NOT NULL CHECK (type IN ('like', 'dislike')),
    feedback   TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_message_feedback_message ON message_feedback(message_id);
CREATE INDEX idx_message_feedback_session ON message_feedback(session_id);

-- 消息附件表（FK 指向 chat_messages）
CREATE TABLE message_attachments (
    id         TEXT PRIMARY KEY,
    message_id TEXT,
    session_id TEXT NOT NULL,
    file_name  TEXT NOT NULL,
    file_path  TEXT NOT NULL,
    file_size  INTEGER NOT NULL DEFAULT 0,
    mime_type  TEXT NOT NULL DEFAULT '',
    url        TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE SET NULL,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_message_attachments_message ON message_attachments(message_id);
CREATE INDEX idx_message_attachments_session ON message_attachments(session_id);
CREATE INDEX idx_message_attachments_created ON message_attachments(created_at DESC);


-- ============================================================
-- 14. 通知中心模块
-- ============================================================

-- 通知历史记录表
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

CREATE INDEX idx_notification_history_user_id ON notification_history(user_id);
CREATE INDEX idx_notification_history_sent_at ON notification_history(sent_at);
CREATE INDEX idx_notification_history_read_status ON notification_history(user_id, read_status);

-- 被动通知队列表
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

CREATE INDEX idx_passive_queue_delivered ON passive_notification_queue(delivered);
CREATE INDEX idx_passive_queue_user_id ON passive_notification_queue(user_id, delivered);

-- 通知设置表
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

-- ============================================================
-- 15. 市场模块
-- ============================================================

-- 已安装扩展表（统一 Skill / Workflow 等扩展类型）
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

CREATE INDEX idx_installed_extensions_type ON installed_extensions(type);
CREATE INDEX idx_installed_extensions_package_id ON installed_extensions(package_id);

-- 市场索引缓存
CREATE TABLE marketplace_index_cache (
    id          TEXT PRIMARY KEY,
    source_url  TEXT NOT NULL UNIQUE,
    index_json  TEXT NOT NULL,
    fetched_at  TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

-- ============================================================
-- 16. DataStore 模块
-- ============================================================

-- 集合表
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

-- 文档表
CREATE TABLE ds_documents (
    id            TEXT PRIMARY KEY,
    collection_id TEXT NOT NULL REFERENCES ds_collections(id) ON DELETE CASCADE,
    data_json     TEXT NOT NULL DEFAULT '{}',
    recorded_at   TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE INDEX idx_ds_documents_collection ON ds_documents(collection_id);
CREATE INDEX idx_ds_documents_recorded_at ON ds_documents(collection_id, recorded_at);

-- DataStore FTS5 全文索引
CREATE VIRTUAL TABLE ds_documents_fts USING fts5(
    document_id, content,
    tokenize='unicode61'
);

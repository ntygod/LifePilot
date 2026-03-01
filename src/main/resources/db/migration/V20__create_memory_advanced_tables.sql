-- V20: 创建记忆系统进阶表 — L4 程序记忆 + 巩固日志 + 遗忘日志

-- ============================================================
-- L4 程序记忆表
-- ============================================================

-- 操作模板表
CREATE TABLE IF NOT EXISTS procedure_templates (
    template_id         TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    description         TEXT NOT NULL,
    trigger_intent      TEXT NOT NULL,
    steps_json          TEXT NOT NULL,
    variables_json      TEXT NOT NULL DEFAULT '{}',
    success_rate        REAL NOT NULL DEFAULT 0.0,
    use_count           INTEGER NOT NULL DEFAULT 0,
    last_used_at        TEXT,
    source_trace_ids_json TEXT NOT NULL DEFAULT '[]',
    created_at          TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_procedure_templates_name
    ON procedure_templates(name);
CREATE INDEX IF NOT EXISTS idx_procedure_templates_success
    ON procedure_templates(success_rate, use_count);

-- 操作模板 FTS5 全文索引
CREATE VIRTUAL TABLE IF NOT EXISTS procedure_templates_fts USING fts5(
    name,
    description,
    trigger_intent,
    content='procedure_templates',
    content_rowid='rowid',
    tokenize='unicode61'
);

-- FTS5 同步触发器
CREATE TRIGGER IF NOT EXISTS procedure_templates_fts_ai
    AFTER INSERT ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(rowid, name, description, trigger_intent)
        VALUES (new.rowid, new.name, new.description, new.trigger_intent);
END;

CREATE TRIGGER IF NOT EXISTS procedure_templates_fts_ad
    AFTER DELETE ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(procedure_templates_fts, rowid, name, description, trigger_intent)
        VALUES ('delete', old.rowid, old.name, old.description, old.trigger_intent);
END;

CREATE TRIGGER IF NOT EXISTS procedure_templates_fts_au
    AFTER UPDATE ON procedure_templates BEGIN
    INSERT INTO procedure_templates_fts(procedure_templates_fts, rowid, name, description, trigger_intent)
        VALUES ('delete', old.rowid, old.name, old.description, old.trigger_intent);
    INSERT INTO procedure_templates_fts(rowid, name, description, trigger_intent)
        VALUES (new.rowid, new.name, new.description, new.trigger_intent);
END;

-- 偏好规则表
CREATE TABLE IF NOT EXISTS preference_rules (
    rule_id             TEXT PRIMARY KEY,
    category            TEXT NOT NULL,
    key                 TEXT NOT NULL,
    value               TEXT NOT NULL,
    confidence          REAL NOT NULL DEFAULT 0.3,
    learned_from_json   TEXT NOT NULL DEFAULT '[]',
    observation_count   INTEGER NOT NULL DEFAULT 1,
    created_at          TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at          TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(category, key)
);

-- 策略模式表
CREATE TABLE IF NOT EXISTS strategy_patterns (
    pattern_id          TEXT PRIMARY KEY,
    situation           TEXT NOT NULL,
    recommended_action  TEXT NOT NULL,
    success_rate        REAL NOT NULL DEFAULT 0.0,
    application_count   INTEGER NOT NULL DEFAULT 0,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- 巩固与遗忘日志表
-- ============================================================

-- 巩固执行日志
CREATE TABLE IF NOT EXISTS memory_consolidation_log (
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

CREATE INDEX IF NOT EXISTS idx_consolidation_log_type_time
    ON memory_consolidation_log(consolidation_type, created_at);

-- 遗忘操作日志
CREATE TABLE IF NOT EXISTS forgetting_log (
    id                  TEXT PRIMARY KEY,
    entity_id           TEXT NOT NULL,
    entity_name         TEXT NOT NULL,
    strategy            TEXT NOT NULL,
    action_taken        TEXT NOT NULL,
    forgetting_priority REAL NOT NULL,
    reason              TEXT,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_forgetting_log_entity
    ON forgetting_log(entity_id);
CREATE INDEX IF NOT EXISTS idx_forgetting_log_time
    ON forgetting_log(created_at);

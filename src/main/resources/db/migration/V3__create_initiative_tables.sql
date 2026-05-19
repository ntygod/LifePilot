-- ============================================================
-- V3: Initiative Engine 主动引擎表
-- ============================================================

-- 想法池
CREATE TABLE IF NOT EXISTS initiative_thoughts (
    id              TEXT PRIMARY KEY,
    intent_key      TEXT NOT NULL,
    kind            TEXT NOT NULL,
    summary         TEXT NOT NULL,
    evidence_json   TEXT NOT NULL DEFAULT '[]',
    confidence      REAL NOT NULL DEFAULT 0.0,
    maturity        REAL NOT NULL DEFAULT 0.0,
    state           TEXT NOT NULL DEFAULT 'BREWING',
    action_pattern  TEXT,
    conversation_id TEXT,
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    mature_at       TEXT,
    expressed_at    TEXT,
    resolved_at     TEXT
);

CREATE INDEX IF NOT EXISTS idx_thoughts_state ON initiative_thoughts(state);
CREATE INDEX IF NOT EXISTS idx_thoughts_intent_key ON initiative_thoughts(intent_key, state);

-- 执行授权
CREATE TABLE IF NOT EXISTS initiative_permissions (
    id              TEXT PRIMARY KEY,
    action_pattern  TEXT NOT NULL UNIQUE,
    description     TEXT NOT NULL,
    allowed_tools   TEXT,
    max_risk        TEXT NOT NULL DEFAULT 'LOW',
    active          INTEGER NOT NULL DEFAULT 1,
    granted_at      TEXT NOT NULL DEFAULT (datetime('now')),
    revoked_at      TEXT
);

-- 执行记录
CREATE TABLE IF NOT EXISTS initiative_executions (
    id              TEXT PRIMARY KEY,
    thought_id      TEXT NOT NULL REFERENCES initiative_thoughts(id),
    permission_id   TEXT REFERENCES initiative_permissions(id),
    action_pattern  TEXT NOT NULL,
    status          TEXT NOT NULL,
    result_summary  TEXT,
    trace_id        TEXT,
    started_at      TEXT NOT NULL DEFAULT (datetime('now')),
    completed_at    TEXT,
    notified_at     TEXT,
    user_response   TEXT
);

CREATE INDEX IF NOT EXISTS idx_executions_thought ON initiative_executions(thought_id);
CREATE INDEX IF NOT EXISTS idx_executions_status ON initiative_executions(action_pattern, status);

-- 对话结果（学习信号）
CREATE TABLE IF NOT EXISTS initiative_outcomes (
    id              TEXT PRIMARY KEY,
    thought_id      TEXT NOT NULL REFERENCES initiative_thoughts(id),
    conversation_id TEXT NOT NULL,
    user_turns      INTEGER NOT NULL DEFAULT 0,
    agent_actions   INTEGER NOT NULL DEFAULT 0,
    outcome_type    TEXT NOT NULL,
    outcome_detail  TEXT,
    duration_seconds INTEGER,
    expressed_at    TEXT NOT NULL,
    resolved_at     TEXT,
    hour_of_day     INTEGER,
    day_of_week     INTEGER,
    was_boundary    INTEGER DEFAULT 0,
    was_idle        INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_outcomes_thought ON initiative_outcomes(thought_id);
CREATE INDEX IF NOT EXISTS idx_outcomes_type ON initiative_outcomes(outcome_type);

-- =============================================================================
-- V2: Agent 挂起-恢复持久化表
-- 存储挂起的 Agent 状态快照，支持应用重启后恢复
-- 创建日期：2026-03-17
-- =============================================================================

CREATE TABLE IF NOT EXISTS suspended_agents (
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

CREATE INDEX idx_suspended_agents_session ON suspended_agents(session_id);
CREATE INDEX idx_suspended_agents_reason  ON suspended_agents(reason_type);

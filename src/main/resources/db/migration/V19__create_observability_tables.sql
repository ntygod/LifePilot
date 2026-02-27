-- =============================================================================
-- V19: 可观测性模块表结构
-- 创建 traces、trace_steps、guardrail_logs、redaction_logs、evaluation_results 表
-- 创建 traces_fts FTS5 虚拟表及同步触发器
-- =============================================================================

-- -----------------------------------------------------------------------------
-- traces 表：顶层追踪记录
-- -----------------------------------------------------------------------------
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


-- -----------------------------------------------------------------------------
-- trace_steps 表：追踪步骤详情
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- guardrail_logs 表：护栏审计日志
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- redaction_logs 表：脱敏审计日志
-- -----------------------------------------------------------------------------
CREATE TABLE redaction_logs (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id            TEXT,
    context             TEXT,
    applied_rules_json  TEXT,
    created_at          TEXT NOT NULL
);

CREATE INDEX idx_redaction_logs_trace_id ON redaction_logs(trace_id);

-- -----------------------------------------------------------------------------
-- evaluation_results 表：轨迹评估结果
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- traces_fts FTS5 虚拟表：全文搜索 goal 和 final_output
-- -----------------------------------------------------------------------------
CREATE VIRTUAL TABLE traces_fts USING fts5(
    trace_id,
    goal,
    final_output,
    content='traces',
    content_rowid='rowid'
);

-- 插入触发器
CREATE TRIGGER traces_ai AFTER INSERT ON traces BEGIN
    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
END;

-- 删除触发器
CREATE TRIGGER traces_ad AFTER DELETE ON traces BEGIN
    INSERT INTO traces_fts(traces_fts, rowid, trace_id, goal, final_output)
    VALUES ('delete', old.rowid, old.trace_id, old.goal, old.final_output);
END;

-- 更新触发器
CREATE TRIGGER traces_au AFTER UPDATE ON traces BEGIN
    INSERT INTO traces_fts(traces_fts, rowid, trace_id, goal, final_output)
    VALUES ('delete', old.rowid, old.trace_id, old.goal, old.final_output);
    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
END;

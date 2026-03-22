-- 评估模块增强：诊断报告、运行元数据、反馈机制
-- @author zsg
-- @since 2026-03-22

-- 评估结果增强字段
ALTER TABLE eval_results ADD COLUMN diagnostic_json TEXT;
ALTER TABLE eval_results ADD COLUMN run_metadata_json TEXT;

-- 评估运行表（存储运行级元数据和基线标记）
CREATE TABLE IF NOT EXISTS eval_runs (
    eval_run_id     TEXT PRIMARY KEY,
    metadata_json   TEXT,
    is_baseline     INTEGER DEFAULT 0,
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_eval_runs_baseline ON eval_runs(is_baseline);

-- 人工反馈表
CREATE TABLE IF NOT EXISTS eval_feedback (
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

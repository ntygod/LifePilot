-- 评估结果表 — 存储 Agent 质量评估的各维度评分、违规项、改进建议
-- 用于 Agentic Evals 框架的评估结果持久化

CREATE TABLE IF NOT EXISTS eval_results (
    eval_id                 TEXT PRIMARY KEY,
    trace_id                TEXT,
    scenario_id             TEXT NOT NULL,
    dimension_scores_json   TEXT NOT NULL,       -- JSON: {"toolSelection": 0.9, ...}
    overall_score           REAL NOT NULL,
    violations_json         TEXT NOT NULL,       -- JSON: ["违规项1", ...]
    suggestions_json        TEXT NOT NULL,       -- JSON: ["建议1", ...]
    llm_judge_score         REAL,
    llm_judge_justification TEXT,
    llm_judge_tokens_used   INTEGER DEFAULT 0,
    git_commit_hash         TEXT,
    git_branch              TEXT,
    eval_run_id             TEXT NOT NULL,
    evaluated_at            TEXT NOT NULL,       -- ISO 8601
    created_at              TEXT NOT NULL        -- ISO 8601
);

CREATE INDEX idx_eval_results_scenario_id ON eval_results(scenario_id);
CREATE INDEX idx_eval_results_eval_run_id ON eval_results(eval_run_id);
CREATE INDEX idx_eval_results_evaluated_at ON eval_results(evaluated_at);

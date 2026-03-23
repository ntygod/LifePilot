-- 每日 Token 聚合表：用于预算护栏按自然日累计统计 Token 消耗

CREATE TABLE daily_token_usage (
    usage_date     TEXT PRIMARY KEY,
    input_tokens   INTEGER NOT NULL DEFAULT 0,
    output_tokens  INTEGER NOT NULL DEFAULT 0,
    total_tokens   INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

CREATE INDEX idx_daily_token_usage_updated_at ON daily_token_usage(updated_at);

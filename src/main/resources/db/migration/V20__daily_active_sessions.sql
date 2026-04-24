-- ZhiWei 工具使用统计：每日活跃会话表
-- V16, 2026-04-23
--
-- 动机：Tier 1 晋升建议 (Tier1AdvisoryJob) 的 coverage 分母要的是
-- "当日任一工具出现过的 distinct session 数"，原来用 tool_usage_stats
-- 的 SUM(session_count) 会被多工具场景稀释 N 倍（若平均每 session 用
-- N 个工具，分母就是 N × 真实 distinct session）。
-- 本表独立跟踪 (stat_date, session_id) 维度，供 AdvisoryJob 准确计算。

CREATE TABLE daily_active_sessions (
    stat_date  TEXT NOT NULL,
    session_id TEXT NOT NULL,
    PRIMARY KEY (stat_date, session_id)
);

CREATE INDEX idx_daily_active_sessions_date ON daily_active_sessions(stat_date);

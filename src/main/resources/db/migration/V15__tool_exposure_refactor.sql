-- ZhiWei 工具暴露机制重构：FTS5 搜索索引 + 使用统计 + Tier 1 晋升建议表
-- V15, 2026-04-23

-- 1. 工具搜索 FTS5 索引
-- tool_id UNINDEXED：主键，不参与分词；description 是主要匹配字段
-- tokenizer unicode61：英文 word-level 分词（工具 metadata 已英文化）
CREATE VIRTUAL TABLE tool_search_index USING fts5(
    tool_id UNINDEXED,
    description,
    tags,
    actions,
    category,
    tokenize = 'unicode61 remove_diacritics 2'
);

-- 2. 工具使用统计（for Tier 1 自动晋升）
CREATE TABLE tool_usage_stats (
    tool_id          TEXT    NOT NULL,
    stat_date        TEXT    NOT NULL,
    session_count    INTEGER NOT NULL DEFAULT 0,
    invocation_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tool_id, stat_date)
);

CREATE INDEX idx_tool_usage_stats_date ON tool_usage_stats(stat_date);

-- 3. Tier 1 晋升建议（Job 生成候选，管理员 UI 审批后写 approved）
CREATE TABLE tier1_advisory (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_id        TEXT    NOT NULL,
    advised_at     TEXT    NOT NULL,
    window_days    INTEGER NOT NULL,
    coverage_ratio REAL    NOT NULL,
    status         TEXT    NOT NULL DEFAULT 'PENDING',
    reviewed_by    TEXT,
    reviewed_at    TEXT,
    CONSTRAINT chk_advisory_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_tier1_advisory_status ON tier1_advisory(status);
CREATE INDEX idx_tier1_advisory_tool ON tier1_advisory(tool_id);

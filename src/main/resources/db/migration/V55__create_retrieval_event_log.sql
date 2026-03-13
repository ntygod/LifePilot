-- 检索事件日志表：记录每次混合检索的耗时、命中数和各路得分
CREATE TABLE IF NOT EXISTS retrieval_event_log (
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

CREATE INDEX IF NOT EXISTS idx_retrieval_event_log_time
    ON retrieval_event_log(created_at);

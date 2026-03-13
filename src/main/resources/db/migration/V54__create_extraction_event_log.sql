-- 提取事件日志表：记录每次 AUDN 实体提取的决策详情
CREATE TABLE IF NOT EXISTS extraction_event_log (
    id                      TEXT PRIMARY KEY,
    session_id              TEXT NOT NULL,
    operation               TEXT NOT NULL,
    entity_name             TEXT NOT NULL,
    entity_type             TEXT NOT NULL,
    extraction_confidence   REAL,
    importance_score        REAL,
    success                 INTEGER NOT NULL DEFAULT 1,
    error_message           TEXT,
    created_at              TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_extraction_event_log_session
    ON extraction_event_log(session_id);
CREATE INDEX IF NOT EXISTS idx_extraction_event_log_time
    ON extraction_event_log(created_at);

-- 工作记忆 WAL 表：用于 L1 工作记忆的持久化日志，防止异常退出时数据丢失。
-- append 时同步写入，flush 成功后按 session_id 批量删除，启动时检查残留记录做恢复。
CREATE TABLE IF NOT EXISTS working_memory_wal (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    slot_type   TEXT    NOT NULL,
    slot_json   TEXT    NOT NULL,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_wm_wal_session_id ON working_memory_wal(session_id);

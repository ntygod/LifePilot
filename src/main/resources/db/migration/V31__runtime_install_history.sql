-- 运行时安装历史审计表 — 记录捆绑 Python 运行时的 install / uninstall / enable / disable 行为。
--
-- 由 RuntimeInstallHistoryRepository 写入，写入失败仅记日志不影响主流程（spec §7）。
-- 索引覆盖按时间倒序查询、按 runtime_kind + action 过滤两种典型读模式。

CREATE TABLE runtime_install_history (
    id           TEXT PRIMARY KEY,
    runtime_kind TEXT NOT NULL,
    version      TEXT NOT NULL,
    action       TEXT NOT NULL,
    status       TEXT NOT NULL,
    error_msg    TEXT,
    duration_ms  INTEGER,
    created_at   TEXT NOT NULL
);

CREATE INDEX idx_runtime_install_history_created_at ON runtime_install_history(created_at);
CREATE INDEX idx_runtime_install_history_kind_action ON runtime_install_history(runtime_kind, action);

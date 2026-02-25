-- ============================================================
-- Gateway 核心框架数据库表
-- 包含：会话管理、审计日志、限流计数、失败消息队列、用户行为
-- ============================================================

-- 1. 网关会话表
CREATE TABLE IF NOT EXISTS gateway_sessions (
    session_id      TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    channel_type    TEXT NOT NULL CHECK (channel_type IN ('cli', 'web', 'wecom', 'dingtalk', 'feishu')),
    state           TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'idle', 'expired', 'closed')),
    total_tokens    INTEGER NOT NULL DEFAULT 0,
    total_requests  INTEGER NOT NULL DEFAULT 0,
    metadata_json   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_active_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_gateway_sessions_user_id ON gateway_sessions(user_id);
CREATE INDEX idx_gateway_sessions_channel_type ON gateway_sessions(channel_type);
CREATE INDEX idx_gateway_sessions_state ON gateway_sessions(state);

-- 2. 审计日志表
CREATE TABLE IF NOT EXISTS gateway_audit_log (
    audit_id                TEXT PRIMARY KEY,
    message_id              TEXT NOT NULL,
    session_id              TEXT,
    channel_type            TEXT NOT NULL,
    user_id                 TEXT NOT NULL,
    request_content_hash    TEXT,
    request_summary         TEXT,
    response_status_code    INTEGER NOT NULL,
    response_summary        TEXT,
    route_type              TEXT CHECK (route_type IN ('fast_path', 'agent', 'error')),
    latency_ms              INTEGER NOT NULL,
    prompt_tokens           INTEGER NOT NULL DEFAULT 0,
    completion_tokens       INTEGER NOT NULL DEFAULT 0,
    total_tokens            INTEGER NOT NULL DEFAULT 0,
    model_id                TEXT,
    middleware_results_json  TEXT,
    created_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_audit_message_id ON gateway_audit_log(message_id);
CREATE INDEX idx_audit_session_id ON gateway_audit_log(session_id);
CREATE INDEX idx_audit_user_id ON gateway_audit_log(user_id);
CREATE INDEX idx_audit_created_at ON gateway_audit_log(created_at);


-- 3. 限流计数器表
CREATE TABLE IF NOT EXISTS rate_limit_counters (
    counter_id      TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    channel_type    TEXT NOT NULL,
    counter_type    TEXT NOT NULL CHECK (counter_type IN ('tokens_per_hour', 'tokens_per_day', 'requests_per_minute')),
    window_start    TEXT NOT NULL,
    window_end      TEXT NOT NULL,
    current_count   INTEGER NOT NULL DEFAULT 0,
    max_count       INTEGER NOT NULL,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_rate_limit_user_channel_type ON rate_limit_counters(user_id, channel_type, counter_type);

-- 4. 失败消息队列表
CREATE TABLE IF NOT EXISTS failed_messages (
    message_id      TEXT PRIMARY KEY,
    channel_type    TEXT NOT NULL,
    user_id         TEXT NOT NULL,
    content_json    TEXT NOT NULL,
    retry_count     INTEGER NOT NULL DEFAULT 0,
    max_retries     INTEGER NOT NULL DEFAULT 3,
    last_error      TEXT,
    next_retry_at   TEXT,
    status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'retrying', 'failed', 'resolved')),
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_failed_status ON failed_messages(status);
CREATE INDEX idx_failed_next_retry ON failed_messages(next_retry_at);

-- 5. 用户行为表
CREATE TABLE IF NOT EXISTS user_behavior (
    user_id             TEXT PRIMARY KEY,
    total_interactions  INTEGER NOT NULL DEFAULT 0,
    security_incidents  INTEGER NOT NULL DEFAULT 0,
    last_incident_at    TEXT,
    first_seen_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_user_behavior_incidents ON user_behavior(security_incidents);

-- ============================================================
-- updated_at 自动更新触发器
-- ============================================================

CREATE TRIGGER trg_gateway_sessions_updated_at
    AFTER UPDATE ON gateway_sessions
    FOR EACH ROW
BEGIN
    UPDATE gateway_sessions
    SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE session_id = NEW.session_id;
END;

CREATE TRIGGER trg_rate_limit_counters_updated_at
    AFTER UPDATE ON rate_limit_counters
    FOR EACH ROW
BEGIN
    UPDATE rate_limit_counters
    SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE counter_id = NEW.counter_id;
END;

CREATE TRIGGER trg_failed_messages_updated_at
    AFTER UPDATE ON failed_messages
    FOR EACH ROW
BEGIN
    UPDATE failed_messages
    SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE message_id = NEW.message_id;
END;

CREATE TRIGGER trg_user_behavior_updated_at
    AFTER UPDATE ON user_behavior
    FOR EACH ROW
BEGIN
    UPDATE user_behavior
    SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE user_id = NEW.user_id;
END;

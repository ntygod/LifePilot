-- 熔断器状态持久化表
CREATE TABLE IF NOT EXISTS circuit_breaker_states (
    provider_capability TEXT PRIMARY KEY,   -- 复合键 "providerId:capabilityType"
    state               TEXT NOT NULL DEFAULT 'CLOSED'
                         CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    failure_count       INTEGER NOT NULL DEFAULT 0,
    last_failure_at     TEXT,               -- 最后失败时间 ISO 8601
    state_changed_at    TEXT NOT NULL,       -- 状态变更时间 ISO 8601
    updated_at          TEXT NOT NULL        -- 最后更新时间 ISO 8601
);

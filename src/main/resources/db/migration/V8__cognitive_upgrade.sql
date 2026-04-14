-- 用户画像表 — 自然语言描述的三维用户理解

CREATE TABLE IF NOT EXISTS proactive_user_profiles (
    user_id              TEXT    NOT NULL PRIMARY KEY,
    work_rhythm          TEXT,
    preferences          TEXT,
    goals                TEXT,
    full_portrait        TEXT,
    conversation_count   INTEGER NOT NULL DEFAULT 0,
    last_consolidated_at TEXT,
    updated_at           TEXT    NOT NULL
);

-- 反思经验表 — 周频自省产出，供下周决策参考

CREATE TABLE IF NOT EXISTS proactive_reflection_experiences (
    id                    TEXT    NOT NULL PRIMARY KEY,
    user_id               TEXT    NOT NULL,
    week_start            TEXT    NOT NULL,
    useful_patterns       TEXT,
    missed_opportunities  TEXT,
    strategy_adjustments  TEXT,
    raw_reflection        TEXT,
    created_at            TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_proactive_reflection_user_week
    ON proactive_reflection_experiences (user_id, week_start DESC);

-- 隐式信号表 — 记录投递后的用户行为信号

CREATE TABLE IF NOT EXISTS proactive_implicit_signals (
    id                TEXT    NOT NULL PRIMARY KEY,
    user_id           TEXT    NOT NULL,
    notification_id   TEXT,
    signal_type       TEXT    NOT NULL,
    behavior_name     TEXT,
    topic_key         TEXT,
    signal_value      REAL    NOT NULL DEFAULT 0,
    evidence          TEXT,
    created_at        TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_proactive_implicit_signals_user
    ON proactive_implicit_signals (user_id, created_at DESC);

-- ============================================================
-- V7: 主动提醒候选与证据持久化
-- ============================================================

CREATE TABLE proactive_reminder_runs (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    topics_collected INTEGER NOT NULL DEFAULT 0,
    decisions_evaluated INTEGER NOT NULL DEFAULT 0,
    reminders_sent INTEGER NOT NULL DEFAULT 0,
    context_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE proactive_reminder_decisions (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    topic_key TEXT NOT NULL,
    title TEXT NOT NULL,
    signal_id TEXT NOT NULL,
    candidate_type TEXT NOT NULL,
    action TEXT NOT NULL,
    decision_reason TEXT,
    rationale TEXT,
    final_score REAL NOT NULL,
    evidence_score REAL NOT NULL,
    timing_score REAL NOT NULL,
    urgency_score REAL NOT NULL,
    user_fit_score REAL NOT NULL,
    actionability_score REAL NOT NULL,
    duplicate_penalty REAL NOT NULL,
    fatigue_penalty REAL NOT NULL,
    suggested_at TEXT,
    next_evaluation_at TEXT,
    notified INTEGER NOT NULL DEFAULT 0,
    notification_id TEXT,
    topic_last_reminded_at TEXT,
    topic_reminders_sent_today INTEGER NOT NULL DEFAULT 0,
    topic_read_count_30d INTEGER NOT NULL DEFAULT 0,
    topic_acted_count_30d INTEGER NOT NULL DEFAULT 0,
    topic_dismissed_count_30d INTEGER NOT NULL DEFAULT 0,
    topic_snoozed_count_30d INTEGER NOT NULL DEFAULT 0,
    topic_not_relevant_count_30d INTEGER NOT NULL DEFAULT 0,
    topic_muted INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (run_id) REFERENCES proactive_reminder_runs(id) ON DELETE CASCADE
);

CREATE TABLE proactive_reminder_evidence (
    id TEXT PRIMARY KEY,
    decision_id TEXT NOT NULL,
    signal_id TEXT NOT NULL,
    signal_kind TEXT NOT NULL,
    confidence_score REAL NOT NULL,
    importance_score REAL NOT NULL,
    evidence_count INTEGER NOT NULL,
    observed_at TEXT NOT NULL,
    relevant_at TEXT,
    preparation_lead_minutes INTEGER,
    preferred_window_start_hour INTEGER,
    preferred_window_end_hour INTEGER,
    anomaly_score REAL NOT NULL DEFAULT 0,
    actionable INTEGER NOT NULL DEFAULT 0,
    resolved INTEGER NOT NULL DEFAULT 0,
    summary TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (decision_id) REFERENCES proactive_reminder_decisions(id) ON DELETE CASCADE
);

CREATE INDEX idx_proactive_reminder_runs_user_started
    ON proactive_reminder_runs(user_id, started_at DESC);

CREATE INDEX idx_proactive_reminder_decisions_run
    ON proactive_reminder_decisions(run_id, created_at ASC);

CREATE INDEX idx_proactive_reminder_decisions_topic
    ON proactive_reminder_decisions(topic_key, created_at DESC);

CREATE INDEX idx_proactive_reminder_decisions_action
    ON proactive_reminder_decisions(action, created_at DESC);

CREATE INDEX idx_proactive_reminder_evidence_decision
    ON proactive_reminder_evidence(decision_id, created_at ASC);

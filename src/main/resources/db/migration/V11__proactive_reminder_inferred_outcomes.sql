CREATE TABLE IF NOT EXISTS proactive_reminder_inferred_outcomes (
    id                TEXT PRIMARY KEY,
    decision_id       TEXT NOT NULL,
    notification_id   TEXT,
    user_id           TEXT NOT NULL,
    topic_key         TEXT NOT NULL,
    outcome_type      TEXT NOT NULL,
    evidence_source   TEXT NOT NULL,
    confidence_score  REAL NOT NULL DEFAULT 0,
    evidence_json     TEXT,
    inferred_at       TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    FOREIGN KEY (decision_id) REFERENCES proactive_reminder_decisions(id) ON DELETE CASCADE,
    FOREIGN KEY (notification_id) REFERENCES notification_history(id) ON DELETE SET NULL,
    UNIQUE (decision_id, outcome_type, evidence_source)
);

CREATE INDEX IF NOT EXISTS idx_proactive_reminder_inferred_outcomes_topic
    ON proactive_reminder_inferred_outcomes(user_id, topic_key, inferred_at DESC);

CREATE INDEX IF NOT EXISTS idx_proactive_reminder_inferred_outcomes_decision
    ON proactive_reminder_inferred_outcomes(decision_id);

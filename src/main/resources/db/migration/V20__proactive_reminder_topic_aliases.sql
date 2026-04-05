CREATE TABLE proactive_reminder_topic_aliases (
    user_id TEXT NOT NULL,
    alias_topic_key TEXT NOT NULL,
    canonical_topic_key TEXT NOT NULL,
    topic_family TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (user_id, alias_topic_key)
);

CREATE INDEX idx_proactive_reminder_topic_aliases_canonical
    ON proactive_reminder_topic_aliases(user_id, canonical_topic_key, updated_at DESC);

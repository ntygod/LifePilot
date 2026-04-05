ALTER TABLE proactive_reminder_inferred_outcomes
    ADD COLUMN attribution_score REAL NOT NULL DEFAULT 1.0;

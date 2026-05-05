ALTER TABLE extraction_event_log ADD COLUMN turn_id TEXT;
ALTER TABLE extraction_event_log ADD COLUMN space_id TEXT;
ALTER TABLE extraction_event_log ADD COLUMN source_entry_id TEXT;

CREATE INDEX IF NOT EXISTS idx_extraction_event_log_turn ON extraction_event_log(turn_id);
CREATE INDEX IF NOT EXISTS idx_extraction_event_log_space ON extraction_event_log(space_id, created_at);

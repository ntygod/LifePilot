ALTER TABLE message_feedback RENAME COLUMN message_id TO entry_id;
DROP INDEX IF EXISTS idx_message_feedback_message;
CREATE INDEX IF NOT EXISTS idx_message_feedback_entry ON message_feedback(entry_id);

ALTER TABLE message_attachments RENAME COLUMN message_id TO entry_id;
DROP INDEX IF EXISTS idx_message_attachments_message;
CREATE INDEX IF NOT EXISTS idx_message_attachments_entry ON message_attachments(entry_id);

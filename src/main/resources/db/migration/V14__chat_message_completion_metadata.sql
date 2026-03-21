ALTER TABLE chat_messages ADD COLUMN completion_mode TEXT;

ALTER TABLE chat_messages ADD COLUMN resumed_from_trace_id TEXT;

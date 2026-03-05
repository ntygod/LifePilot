-- Soft delete flag for workflow_definitions.
-- We keep the row to preserve FK integrity for workflow_instances history,
-- but hide deleted definitions from normal listing/loading.

ALTER TABLE workflow_definitions
    ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;


-- 工作流引擎数据库表

CREATE TABLE IF NOT EXISTS workflow_definitions (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    description     TEXT,
    version         TEXT,
    enabled         INTEGER NOT NULL DEFAULT 1,
    definition_yaml TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS workflow_instances (
    id                 TEXT PRIMARY KEY,
    workflow_id        TEXT NOT NULL,
    state              TEXT NOT NULL,
    input_json         TEXT,
    context_json       TEXT,
    current_step_index INTEGER NOT NULL DEFAULT 0,
    started_at         TEXT,
    completed_at       TEXT,
    failure_reason     TEXT,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL,
    FOREIGN KEY (workflow_id) REFERENCES workflow_definitions(id)
);

CREATE TABLE IF NOT EXISTS workflow_step_logs (
    id            TEXT PRIMARY KEY,
    instance_id   TEXT NOT NULL,
    step_id       TEXT NOT NULL,
    step_type     TEXT NOT NULL,
    state         TEXT NOT NULL,
    attempt       INTEGER NOT NULL DEFAULT 1,
    input_json    TEXT,
    output_json   TEXT,
    error_message TEXT,
    started_at    TEXT,
    completed_at  TEXT,
    duration_ms   INTEGER,
    created_at    TEXT NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES workflow_instances(id)
);

CREATE INDEX idx_workflow_instances_workflow_id ON workflow_instances(workflow_id);
CREATE INDEX idx_workflow_instances_state ON workflow_instances(state);
CREATE INDEX idx_workflow_step_logs_instance_id ON workflow_step_logs(instance_id);

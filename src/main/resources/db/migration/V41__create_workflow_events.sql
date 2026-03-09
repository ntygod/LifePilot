-- 工作流审计事件表
CREATE TABLE workflow_events (
    id          TEXT PRIMARY KEY,
    instance_id TEXT NOT NULL,
    workflow_id TEXT NOT NULL,
    type        TEXT NOT NULL,
    step_id     TEXT,
    data_json   TEXT,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now')),
    FOREIGN KEY (instance_id) REFERENCES workflow_instances(id)
);

CREATE INDEX idx_workflow_events_instance_id ON workflow_events(instance_id);
CREATE INDEX idx_workflow_events_type ON workflow_events(type);
CREATE INDEX idx_workflow_events_created_at ON workflow_events(created_at);

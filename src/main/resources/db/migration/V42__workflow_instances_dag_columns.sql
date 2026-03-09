-- 重建 workflow_instances 表：删除 current_step_index，新增 DAG 状态追踪列

-- 先删除依赖 workflow_instances 的外键表的索引和表引用
-- workflow_events 和 workflow_step_logs 都有 FK 到 workflow_instances

-- 1. 重建 workflow_events（因为 FK 指向 workflow_instances）
CREATE TABLE workflow_events_backup AS SELECT * FROM workflow_events;
DROP TABLE workflow_events;

-- 2. 重建 workflow_step_logs（因为 FK 指向 workflow_instances）
CREATE TABLE workflow_step_logs_backup AS SELECT * FROM workflow_step_logs;
DROP TABLE workflow_step_logs;

-- 3. 重建 workflow_instances 主表
CREATE TABLE workflow_instances_new (
    id                       TEXT PRIMARY KEY,
    workflow_id              TEXT NOT NULL,
    state                    TEXT NOT NULL,
    input_json               TEXT,
    context_json             TEXT,
    completed_step_ids_json  TEXT NOT NULL DEFAULT '[]',
    pending_approval_step_id TEXT,
    started_at               TEXT,
    completed_at             TEXT,
    failure_reason           TEXT,
    created_at               TEXT NOT NULL,
    updated_at               TEXT NOT NULL,
    FOREIGN KEY (workflow_id) REFERENCES workflow_definitions(id)
);

INSERT INTO workflow_instances_new
    (id, workflow_id, state, input_json, context_json,
     completed_step_ids_json, pending_approval_step_id,
     started_at, completed_at, failure_reason, created_at, updated_at)
SELECT id, workflow_id, state, input_json, context_json,
       '[]', NULL,
       started_at, completed_at, failure_reason, created_at, updated_at
FROM workflow_instances;

DROP TABLE workflow_instances;
ALTER TABLE workflow_instances_new RENAME TO workflow_instances;

CREATE INDEX idx_workflow_instances_workflow_id ON workflow_instances(workflow_id);
CREATE INDEX idx_workflow_instances_state ON workflow_instances(state);

-- 4. 恢复 workflow_step_logs
CREATE TABLE workflow_step_logs (
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

INSERT INTO workflow_step_logs SELECT * FROM workflow_step_logs_backup;
DROP TABLE workflow_step_logs_backup;

CREATE INDEX idx_workflow_step_logs_instance_id ON workflow_step_logs(instance_id);
CREATE INDEX idx_workflow_step_logs_step_id ON workflow_step_logs(step_id);

-- 5. 恢复 workflow_events
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

INSERT INTO workflow_events SELECT * FROM workflow_events_backup;
DROP TABLE workflow_events_backup;

CREATE INDEX idx_workflow_events_instance_id ON workflow_events(instance_id);
CREATE INDEX idx_workflow_events_type ON workflow_events(type);
CREATE INDEX idx_workflow_events_created_at ON workflow_events(created_at);

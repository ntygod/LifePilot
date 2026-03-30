CREATE TABLE IF NOT EXISTS channel_plugins (
    plugin_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    vendor TEXT,
    platform TEXT NOT NULL,
    connector_mode TEXT NOT NULL,
    descriptor_json TEXT NOT NULL,
    installed_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_channel_plugins_platform
    ON channel_plugins(platform);

CREATE TABLE IF NOT EXISTS channel_instances (
    instance_id TEXT PRIMARY KEY,
    plugin_id TEXT NOT NULL,
    platform TEXT NOT NULL,
    display_name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL,
    config_json TEXT,
    routing_policy_json TEXT,
    last_heartbeat_at TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (plugin_id) REFERENCES channel_plugins(plugin_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_channel_instances_plugin_id
    ON channel_instances(plugin_id);

CREATE INDEX IF NOT EXISTS idx_channel_instances_platform
    ON channel_instances(platform);

CREATE INDEX IF NOT EXISTS idx_channel_instances_status
    ON channel_instances(status);

CREATE TABLE IF NOT EXISTS channel_instance_secrets (
    instance_id TEXT PRIMARY KEY,
    secret_json TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES channel_instances(instance_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS channel_instance_events (
    id TEXT PRIMARY KEY,
    instance_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    message TEXT,
    payload_json TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES channel_instances(instance_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_channel_instance_events_instance_id
    ON channel_instance_events(instance_id);

CREATE INDEX IF NOT EXISTS idx_channel_instance_events_created_at
    ON channel_instance_events(created_at);

CREATE TABLE memory_spaces (
    id            TEXT PRIMARY KEY,
    space_key     TEXT NOT NULL UNIQUE,
    space_type    TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    owner_type    TEXT,
    owner_id      TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE INDEX idx_memory_spaces_type ON memory_spaces(space_type);
CREATE INDEX idx_memory_spaces_owner ON memory_spaces(owner_type, owner_id);

CREATE TABLE memory_space_knowledge_bases (
    memory_space_id  TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,
    created_at       TEXT NOT NULL,
    PRIMARY KEY (memory_space_id, knowledge_base_id),
    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE TABLE memory_space_datastores (
    memory_space_id TEXT NOT NULL,
    datastore_id    TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    PRIMARY KEY (memory_space_id, datastore_id),
    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id) ON DELETE CASCADE,
    FOREIGN KEY (datastore_id) REFERENCES ds_collections(id) ON DELETE CASCADE
);

CREATE TABLE chat_turn_memory_snapshots (
    turn_id                          TEXT PRIMARY KEY,
    session_id                       TEXT NOT NULL,
    personal_space_id                TEXT,
    experience_space_id              TEXT,
    domain_write_space_id            TEXT,
    read_space_ids_json              TEXT NOT NULL DEFAULT '[]',
    effective_knowledge_base_ids_json TEXT NOT NULL DEFAULT '[]',
    effective_datastore_ids_json     TEXT NOT NULL DEFAULT '[]',
    personal_learning_enabled        INTEGER NOT NULL DEFAULT 1,
    domain_learning_enabled          INTEGER NOT NULL DEFAULT 0,
    experience_learning_enabled      INTEGER NOT NULL DEFAULT 1,
    resolution_source_json           TEXT NOT NULL DEFAULT '{}',
    created_at                       TEXT NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES chat_turns(turn_id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE,
    FOREIGN KEY (personal_space_id) REFERENCES memory_spaces(id),
    FOREIGN KEY (experience_space_id) REFERENCES memory_spaces(id),
    FOREIGN KEY (domain_write_space_id) REFERENCES memory_spaces(id)
);

CREATE INDEX idx_chat_turn_memory_snapshots_session
    ON chat_turn_memory_snapshots(session_id, created_at DESC);

INSERT OR IGNORE INTO memory_spaces (
    id, space_key, space_type, display_name, owner_type, owner_id, metadata_json, created_at, updated_at
) VALUES (
    'memory-space-personal-default',
    'personal:default',
    'PERSONAL',
    '个人记忆',
    'SYSTEM',
    'default',
    '{}',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
);

INSERT OR IGNORE INTO memory_spaces (
    id, space_key, space_type, display_name, owner_type, owner_id, metadata_json, created_at, updated_at
) VALUES (
    'memory-space-experience-default',
    'agent:default',
    'EXPERIENCE',
    'Agent经验',
    'SYSTEM',
    'default',
    '{}',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
);

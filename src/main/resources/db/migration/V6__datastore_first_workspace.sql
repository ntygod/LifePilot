-- ============================================================
-- V6: Datastore First 工作区（内部知识库 + 默认知识库指针）
-- ============================================================

ALTER TABLE ds_collections
    ADD COLUMN default_knowledge_base_id TEXT;

ALTER TABLE knowledge_bases
    ADD COLUMN system_managed INTEGER NOT NULL DEFAULT 0;

ALTER TABLE knowledge_bases
    ADD COLUMN owner_datastore_id TEXT;

CREATE INDEX idx_knowledge_bases_owner_datastore
    ON knowledge_bases(owner_datastore_id)
    WHERE owner_datastore_id IS NOT NULL;

CREATE INDEX idx_knowledge_bases_system_managed
    ON knowledge_bases(system_managed);

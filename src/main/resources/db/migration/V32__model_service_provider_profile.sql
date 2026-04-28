-- V32__model_service_provider_profile.sql
-- 项目未上线，model_services 表直接重建，不做数据迁移。
-- 新 schema 引入 ProviderProfile 数据驱动 + 推理模型配置。
--
-- 关键改动：
--   * provider_type → profile_id（ProviderProfile 主键，路由协议特性的唯一锚点）
--   * 新增 is_reasoning / thinking_mode 控制推理模型行为
--   * 删除冗余的本地索引，改用 (kind, enabled) 与 profile_id 索引

DROP TABLE IF EXISTS model_services;

CREATE TABLE model_services (
    id                            TEXT PRIMARY KEY,
    kind                          TEXT NOT NULL CHECK (kind IN ('GENERATION', 'EMBEDDING', 'RERANK')),
    profile_id                    TEXT NOT NULL,
    api_url                       TEXT NOT NULL,
    api_key                       TEXT,
    model_name                    TEXT NOT NULL,
    timeout_seconds               INTEGER NOT NULL DEFAULT 30,
    priority                      INTEGER NOT NULL DEFAULT 0,
    enabled                       INTEGER NOT NULL DEFAULT 1,
    is_reasoning                  INTEGER NOT NULL DEFAULT 0,
    thinking_mode                 TEXT    NOT NULL DEFAULT 'AUTO',
    supported_scenes_json         TEXT NOT NULL DEFAULT '[]',
    generation_capabilities_json  TEXT NOT NULL DEFAULT '[]',
    metadata_json                 TEXT NOT NULL DEFAULT '{}',
    display_name                  TEXT,
    description                   TEXT,
    created_at                    TEXT NOT NULL,
    updated_at                    TEXT NOT NULL
);

CREATE INDEX idx_model_services_kind     ON model_services(kind, enabled);
CREATE INDEX idx_model_services_profile  ON model_services(profile_id);

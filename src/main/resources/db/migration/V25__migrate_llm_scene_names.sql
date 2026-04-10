-- 迁移 LLM Scene 名称：合并冗余场景，统一前后端命名
-- query_enhance → knowledge_extraction
-- proactive_reminder → chat
-- 清理 GENERATION 类型服务中的 embedding / knowledge_rerank

-- ========== model_services.supported_scenes_json ==========

-- query_enhance → knowledge_extraction（先处理已包含 knowledge_extraction 的，直接删除 query_enhance）
UPDATE model_services
SET supported_scenes_json = REPLACE(REPLACE(supported_scenes_json, ',"query_enhance"', ''), '"query_enhance",', ''),
    updated_at = datetime('now')
WHERE supported_scenes_json LIKE '%query_enhance%'
  AND supported_scenes_json LIKE '%knowledge_extraction%';

-- query_enhance → knowledge_extraction（不包含 knowledge_extraction 的，替换）
UPDATE model_services
SET supported_scenes_json = REPLACE(supported_scenes_json, '"query_enhance"', '"knowledge_extraction"'),
    updated_at = datetime('now')
WHERE supported_scenes_json LIKE '%query_enhance%';

-- proactive_reminder → chat（先处理已包含 chat 的，直接删除 proactive_reminder）
UPDATE model_services
SET supported_scenes_json = REPLACE(REPLACE(supported_scenes_json, ',"proactive_reminder"', ''), '"proactive_reminder",', ''),
    updated_at = datetime('now')
WHERE supported_scenes_json LIKE '%proactive_reminder%'
  AND supported_scenes_json LIKE '%"chat"%';

-- proactive_reminder → chat（不包含 chat 的，替换）
UPDATE model_services
SET supported_scenes_json = REPLACE(supported_scenes_json, '"proactive_reminder"', '"chat"'),
    updated_at = datetime('now')
WHERE supported_scenes_json LIKE '%proactive_reminder%';

-- 清理 GENERATION 类型服务中的 embedding
UPDATE model_services
SET supported_scenes_json = REPLACE(REPLACE(REPLACE(supported_scenes_json,
    ',"embedding"', ''), '"embedding",', ''), '["embedding"]', '[]'),
    updated_at = datetime('now')
WHERE supported_scenes_json LIKE '%"embedding"%' AND kind = 'GENERATION';

-- 清理 GENERATION 类型服务中的 knowledge_rerank
UPDATE model_services
SET supported_scenes_json = REPLACE(REPLACE(REPLACE(supported_scenes_json,
    ',"knowledge_rerank"', ''), '"knowledge_rerank",', ''), '["knowledge_rerank"]', '[]'),
    updated_at = datetime('now')
WHERE supported_scenes_json LIKE '%"knowledge_rerank"%' AND kind = 'GENERATION';

-- ========== generation_settings.scene_service_bindings_json ==========

UPDATE generation_settings
SET scene_service_bindings_json = REPLACE(scene_service_bindings_json, '"query_enhance"', '"knowledge_extraction"'),
    updated_at = datetime('now')
WHERE scene_service_bindings_json LIKE '%query_enhance%';

UPDATE generation_settings
SET scene_service_bindings_json = REPLACE(scene_service_bindings_json, '"proactive_reminder"', '"chat"'),
    updated_at = datetime('now')
WHERE scene_service_bindings_json LIKE '%proactive_reminder%';

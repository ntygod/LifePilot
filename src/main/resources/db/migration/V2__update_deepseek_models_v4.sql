-- DeepSeek V4 模型更新（2026-04 发布）
-- 旧模型 deepseek-chat / deepseek-reasoner 已被 DeepSeek 标记为 deprecated（2026-07-24 下线）
-- 新模型：deepseek-v4-pro（旗舰）、deepseek-v4-flash（快速/低成本，支持思考模式）

-- 更新供应商模板描述
UPDATE model_service_vendor_templates
SET description = 'OpenAI 兼容 API，预置 deepseek-v4-pro 与 deepseek-v4-flash。',
    default_max_context_window = 1000000,
    updated_at = datetime('now')
WHERE vendor_key = 'deepseek';

-- 删除旧模型预置
DELETE FROM model_service_model_presets
WHERE vendor_key = 'deepseek' AND model_value IN ('deepseek-chat', 'deepseek-reasoner');

-- 插入新模型预置
INSERT INTO model_service_model_presets (
    vendor_key, kind, model_value, label, recommended,
    capabilities_json, scenes_json,
    supports_streaming, max_context_window, embedding_dimension, sort_order,
    created_at, updated_at
) VALUES
('deepseek', 'GENERATION', 'deepseek-v4-pro', 'DeepSeek V4 Pro', 1,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation"]',
 1, 1000000, NULL, 10, datetime('now'), datetime('now')),
('deepseek', 'GENERATION', 'deepseek-v4-flash', 'DeepSeek V4 Flash', 0,
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
 '["chat","agent_react","knowledge_extraction","memory_compression","skill_generation","session-title","conversation-summary"]',
 1, 1000000, NULL, 20, datetime('now'), datetime('now'));

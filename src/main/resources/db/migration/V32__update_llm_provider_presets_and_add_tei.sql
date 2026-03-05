-- V32: 调整 LLM 预设 Provider 配置，并新增 TEI 模板
-- 1. 关闭所有预设 Provider 的默认启用状态
-- 2. 每个厂商仅保留一个主要预设（删除部分重复项）
-- 3. 新增 TEI 预设，用于本地 Text Embeddings Inference 服务

-- 1) 将所有预设 Provider 设为未启用，由用户自行选择启用
UPDATE llm_providers
SET enabled = 0
WHERE is_preset = 1;

-- 2) 删除同一厂商下的部分重复预设，只保留能力更强的一个
--   - Qwen: 保留 qwen-plus，移除 qwen-turbo
--   - OpenAI: 保留 openai-gpt-4，移除 openai-gpt-3.5
DELETE FROM llm_providers
WHERE is_preset = 1
  AND id IN ('qwen-turbo', 'openai-gpt-3.5');

-- 3) 确保本地 Ollama 预设存在（如果之前迁移未成功，可在此补齐）
INSERT OR IGNORE INTO llm_providers (
    id, type, api_url, api_key, model_name, timeout_seconds, priority,
    scenes, capabilities, enabled, cost_per_input_token, cost_per_output_token,
    max_context_window, embedding_dimension, supports_streaming, is_preset,
    display_name, description, created_at, updated_at
) VALUES
('ollama-qwen2.5', 'OLLAMA', 'http://localhost:11434', NULL, 'qwen2.5:7b', 60, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","memory_compression","proactive_reasoning","code_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 0, 0, 32768, NULL, 1, 1,
 'Ollama Qwen2.5', '本地运行的 Qwen2.5 模型，无需 API Key', datetime('now'), datetime('now')),

('ollama-nomic-embed', 'OLLAMA', 'http://localhost:11434', NULL, 'nomic-embed-text:v1.5', 30, 0,
 '["embedding"]', '["EMBEDDING"]', 0, 0, 0, 8192, 1024, 0, 1,
 'Ollama Nomic Embed', '本地运行的 Nomic Embed 向量模型', datetime('now'), datetime('now'));

-- 4) 新增 TEI 预设（仅向量嵌入能力，默认不启用）
INSERT OR IGNORE INTO llm_providers (
    id, type, api_url, api_key, model_name, timeout_seconds, priority,
    scenes, capabilities, enabled, cost_per_input_token, cost_per_output_token,
    max_context_window, embedding_dimension, supports_streaming, is_preset,
    display_name, description, created_at, updated_at
) VALUES
('tei-embedding', 'TEI', 'http://localhost:8080/v1', NULL, 'bge-base-en-v1.5', 30, 0,
 '["embedding"]', '["EMBEDDING"]', 0, 0, 0, 8192, 1024, 0, 1,
 'TEI Embedding (本地)', '本地部署的 Text Embeddings Inference 服务，需要根据实际地址与模型名称调整', datetime('now'), datetime('now'));


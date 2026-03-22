-- ============================================================
-- V13: 更新预置 LLM Provider 为 2026 年主流模型
-- ============================================================
-- 问题：原预置模型过时（gpt-4 8K、claude-3-sonnet、deepseek-chat 16K、glm-4 8K、ernie-bot-turbo 8K）
-- 方案：更新为 2026 年主流模型，修正上下文窗口、成本、能力参数

-- 1. 更新 DeepSeek：deepseek-chat → deepseek-v3（128K 窗口）
UPDATE llm_providers SET
    model_name = 'deepseek-v3',
    max_context_window = 131072,
    timeout_seconds = 60,
    cost_per_input_token = 2,
    cost_per_output_token = 8,
    capabilities = '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
    display_name = 'DeepSeek V3',
    description = 'DeepSeek V3 对话模型（128K 上下文），需要配置 API Key',
    updated_at = datetime('now')
WHERE id = 'deepseek-chat' AND is_preset = 1;

-- 2. 更新通义千问：qwen-plus → qwen3-plus（131K 窗口）
UPDATE llm_providers SET
    model_name = 'qwen3-plus',
    max_context_window = 131072,
    timeout_seconds = 60,
    cost_per_input_token = 8,
    cost_per_output_token = 8,
    capabilities = '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
    display_name = '通义千问3 Plus',
    description = '阿里云通义千问3增强模型（128K 上下文），需要配置 API Key',
    updated_at = datetime('now')
WHERE id = 'qwen-plus' AND is_preset = 1;

-- 3. 更新智谱：glm-4 → glm-4-plus（128K 窗口）
UPDATE llm_providers SET
    model_name = 'glm-4-plus',
    max_context_window = 131072,
    timeout_seconds = 60,
    cost_per_input_token = 50,
    cost_per_output_token = 50,
    capabilities = '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
    display_name = '智谱 GLM-4-Plus',
    description = '智谱 AI GLM-4-Plus 模型（128K 上下文），需要配置 API Key',
    updated_at = datetime('now')
WHERE id = 'glm-4' AND is_preset = 1;

-- 4. 更新文心：ernie-bot-turbo → ernie-4.5-turbo（128K 窗口，支持 function calling）
UPDATE llm_providers SET
    model_name = 'ernie-4.5-turbo-128k',
    max_context_window = 131072,
    timeout_seconds = 60,
    cost_per_input_token = 4,
    cost_per_output_token = 8,
    capabilities = '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
    display_name = '文心一言 4.5 Turbo',
    description = '百度文心一言 4.5 Turbo 模型（128K 上下文），需要配置 API Key',
    updated_at = datetime('now')
WHERE id = 'wenxin-ernie' AND is_preset = 1;

-- 5. 更新 OpenAI：gpt-4 → gpt-4.1（1M 窗口）
UPDATE llm_providers SET
    model_name = 'gpt-4.1',
    max_context_window = 1048576,
    timeout_seconds = 60,
    cost_per_input_token = 200,
    cost_per_output_token = 800,
    capabilities = '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
    display_name = 'OpenAI GPT-4.1',
    description = 'OpenAI GPT-4.1 模型（1M 上下文），需要配置 API Key',
    updated_at = datetime('now')
WHERE id = 'openai-gpt-4' AND is_preset = 1;

-- 6. 更新 Anthropic：claude-3-sonnet → claude-sonnet-4-6（200K 窗口）
UPDATE llm_providers SET
    model_name = 'claude-sonnet-4-6',
    api_url = 'https://api.anthropic.com/v1',
    max_context_window = 204800,
    timeout_seconds = 60,
    cost_per_input_token = 300,
    cost_per_output_token = 1500,
    capabilities = '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]',
    display_name = 'Anthropic Claude Sonnet 4.6',
    description = 'Anthropic Claude Sonnet 4.6 模型（200K 上下文），需要配置 API Key',
    updated_at = datetime('now')
WHERE id = 'anthropic-claude' AND is_preset = 1;

-- 7. 更新本地 Ollama：qwen2.5:7b → qwen3:8b（128K 窗口）
UPDATE llm_providers SET
    model_name = 'qwen3:8b',
    max_context_window = 131072,
    timeout_seconds = 120,
    capabilities = '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]',
    display_name = 'Ollama Qwen3 8B',
    description = '本地运行的 Qwen3 8B 模型（128K 上下文），无需 API Key',
    updated_at = datetime('now')
WHERE id = 'ollama-qwen2.5' AND is_preset = 1;

-- 8. 新增 DeepSeek R1 推理模型
INSERT OR IGNORE INTO llm_providers (
    id, type, api_url, api_key, model_name, timeout_seconds, priority,
    scenes, capabilities, enabled, cost_per_input_token, cost_per_output_token,
    max_context_window, embedding_dimension, supports_streaming, is_preset,
    display_name, description, created_at, updated_at
) VALUES (
    'deepseek-r1', 'DEEPSEEK', 'https://api.deepseek.com', NULL, 'deepseek-reasoner', 120, 1,
    '["task_planning","code_generation","agent_reasoning"]',
    '["CHAT","STRUCTURED_OUTPUT","STREAMING"]', 0, 4, 16, 131072, NULL, 1, 1,
    'DeepSeek R1', 'DeepSeek R1 推理模型（128K 上下文），适合复杂推理任务，需要配置 API Key',
    datetime('now'), datetime('now')
);

-- 9. 新增 OpenAI GPT-4o-mini 轻量模型
INSERT OR IGNORE INTO llm_providers (
    id, type, api_url, api_key, model_name, timeout_seconds, priority,
    scenes, capabilities, enabled, cost_per_input_token, cost_per_output_token,
    max_context_window, embedding_dimension, supports_streaming, is_preset,
    display_name, description, created_at, updated_at
) VALUES (
    'openai-gpt-4o-mini', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', NULL, 'gpt-4o-mini', 30, 2,
    '["chat","knowledge_extraction","memory_compression","knowledge_rerank","document_summary"]',
    '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]', 0, 15, 60, 131072, NULL, 1, 1,
    'OpenAI GPT-4o Mini', 'OpenAI GPT-4o Mini 轻量模型（128K 上下文），适合轻量任务，需要配置 API Key',
    datetime('now'), datetime('now')
);

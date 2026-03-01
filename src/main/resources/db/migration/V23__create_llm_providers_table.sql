-- V23: 创建 LLM Provider 配置表
-- 用于存储 LLM Provider 配置信息，支持数据库动态管理

CREATE TABLE IF NOT EXISTS llm_providers (
    id                      TEXT PRIMARY KEY,
    type                    TEXT NOT NULL,
    api_url                 TEXT NOT NULL,
    api_key                 TEXT,  -- 加密存储，可为空（本地模型）
    model_name              TEXT NOT NULL,
    timeout_seconds         INTEGER NOT NULL DEFAULT 30,
    priority                INTEGER NOT NULL DEFAULT 0,
    scenes                  TEXT NOT NULL DEFAULT '[]',  -- JSON 数组
    capabilities            TEXT NOT NULL DEFAULT '["CHAT"]',  -- JSON 数组
    enabled                 INTEGER NOT NULL DEFAULT 1,
    cost_per_input_token    INTEGER NOT NULL DEFAULT 0,
    cost_per_output_token   INTEGER NOT NULL DEFAULT 0,
    max_context_window      INTEGER NOT NULL DEFAULT 4096,
    embedding_dimension     INTEGER,  -- 仅 Embedding 模型需要
    supports_streaming      INTEGER NOT NULL DEFAULT 0,
    is_preset               INTEGER NOT NULL DEFAULT 0,  -- 是否为预设置供应商
    display_name            TEXT,  -- 显示名称（可选）
    description             TEXT,  -- 描述（可选）
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_providers_enabled ON llm_providers(enabled);
CREATE INDEX IF NOT EXISTS idx_llm_providers_type ON llm_providers(type);
CREATE INDEX IF NOT EXISTS idx_llm_providers_preset ON llm_providers(is_preset);

-- 插入预设置的国内外常用供应商模板（仅模板，需要用户配置 API Key）
INSERT OR IGNORE INTO llm_providers (
    id, type, api_url, api_key, model_name, timeout_seconds, priority,
    scenes, capabilities, enabled, cost_per_input_token, cost_per_output_token,
    max_context_window, supports_streaming, is_preset, display_name, description,
    created_at, updated_at
) VALUES
-- 本地模型
('ollama-qwen2.5', 'OLLAMA', 'http://localhost:11434', NULL, 'qwen2.5:7b', 60, 0,
 '["intent_understanding","task_planning","knowledge_extraction","chat","memory_compression","proactive_reasoning","code_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 1, 0, 0, 32768, 1, 1,
 'Ollama Qwen2.5', '本地运行的 Qwen2.5 模型，无需 API Key', datetime('now'), datetime('now')),

('ollama-nomic-embed', 'OLLAMA', 'http://localhost:11434', NULL, 'nomic-embed-text:v1.5', 30, 0,
 '["embedding"]', '["EMBEDDING"]', 1, 0, 0, 8192, 0, 1,
 'Ollama Nomic Embed', '本地运行的 Nomic Embed 向量模型', datetime('now'), datetime('now')),

-- 国内供应商
('deepseek-chat', 'DEEPSEEK', 'https://api.deepseek.com', NULL, 'deepseek-chat', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 14, 28, 16384, 1, 1,
 'DeepSeek Chat', 'DeepSeek 对话模型，需要配置 API Key', datetime('now'), datetime('now')),

('qwen-turbo', 'QWEN', 'https://dashscope.aliyuncs.com/compatible-mode/v1', NULL, 'qwen-turbo', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat"]',
 '["CHAT","STREAMING"]', 0, 8, 8, 8192, 1, 1,
 '通义千问 Turbo', '阿里云通义千问快速模型，需要配置 API Key', datetime('now'), datetime('now')),

('qwen-plus', 'QWEN', 'https://dashscope.aliyuncs.com/compatible-mode/v1', NULL, 'qwen-plus', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 12, 12, 32768, 1, 1,
 '通义千问 Plus', '阿里云通义千问增强模型，需要配置 API Key', datetime('now'), datetime('now')),

('glm-4', 'GLM', 'https://open.bigmodel.cn/api/paas/v4', NULL, 'glm-4', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 10, 10, 8192, 1, 1,
 '智谱 GLM-4', '智谱 AI GLM-4 模型，需要配置 API Key', datetime('now'), datetime('now')),

('wenxin-ernie', 'WENXIN', 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat', NULL, 'ernie-bot-turbo', 30, 1,
 '["intent_understanding","task_planning","knowledge_extraction","chat"]',
 '["CHAT","STREAMING"]', 0, 12, 12, 8192, 1, 1,
 '文心一言', '百度文心一言模型，需要配置 API Key', datetime('now'), datetime('now')),

-- 国外供应商
('openai-gpt-4', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', NULL, 'gpt-4', 30, 2,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING","VISION"]', 0, 3000, 6000, 8192, 1, 1,
 'OpenAI GPT-4', 'OpenAI GPT-4 模型，需要配置 API Key', datetime('now'), datetime('now')),

('openai-gpt-3.5', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', NULL, 'gpt-3.5-turbo', 30, 2,
 '["intent_understanding","task_planning","knowledge_extraction","chat"]',
 '["CHAT","STREAMING"]', 0, 15, 20, 4096, 1, 1,
 'OpenAI GPT-3.5 Turbo', 'OpenAI GPT-3.5 Turbo 模型，需要配置 API Key', datetime('now'), datetime('now')),

('anthropic-claude', 'OPENAI_COMPATIBLE', 'https://api.anthropic.com/v1', NULL, 'claude-3-sonnet-20240229', 30, 2,
 '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation"]',
 '["CHAT","STRUCTURED_OUTPUT","FUNCTION_CALLING","STREAMING"]', 0, 300, 1500, 200000, 1, 1,
 'Anthropic Claude', 'Anthropic Claude 模型，需要配置 API Key', datetime('now'), datetime('now'));

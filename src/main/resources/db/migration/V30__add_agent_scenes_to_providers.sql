-- V30: 为预设 Provider 添加 Agent 相关场景
-- 更新所有预设 Provider 的 scenes 字段，添加 agent-reasoning、agent-tool-calling、agent-generation 场景
-- 只更新预设 Provider（is_preset = 1），且只更新还没有包含 agent-reasoning 场景的 Provider

-- 更新 ollama-qwen2.5（本地模型，已启用）
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","memory_compression","proactive_reasoning","code_generation","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'ollama-qwen2.5' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

-- 更新 deepseek-chat
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'deepseek-chat' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

-- 更新 qwen-turbo
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'qwen-turbo' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

-- 更新 qwen-plus
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'qwen-plus' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

-- 更新 glm-4
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'glm-4' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

-- 更新 wenxin-ernie
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'wenxin-ernie' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

-- 更新 openai-gpt-4
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'openai-gpt-4' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

-- 更新 openai-gpt-3.5
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'openai-gpt-3.5' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

-- 更新 anthropic-claude
UPDATE llm_providers
SET scenes = '["intent_understanding","task_planning","knowledge_extraction","chat","code_generation","agent-reasoning","agent-tool-calling","agent-generation"]'
WHERE id = 'anthropic-claude' 
  AND is_preset = 1 
  AND scenes NOT LIKE '%agent-reasoning%';

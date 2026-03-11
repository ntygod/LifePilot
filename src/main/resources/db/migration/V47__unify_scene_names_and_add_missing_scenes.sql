-- V47: 统一场景命名为下划线风格 + 补齐缺失场景
-- 1. 将 agent-reasoning → agent_reasoning, agent-tool-calling → agent_tool_calling,
--    agent-generation → agent_generation, skill-generation → skill_generation
-- 2. 为所有 CHAT 类 Provider 补齐 knowledge_rerank、document_summary、skill_generation、memory_compression

-- 统一命名：替换 hyphen 为 underscore
UPDATE llm_providers
SET scenes = REPLACE(
    REPLACE(
        REPLACE(
            REPLACE(scenes, 'agent-reasoning', 'agent_reasoning'),
            'agent-tool-calling', 'agent_tool_calling'),
        'agent-generation', 'agent_generation'),
    'skill-generation', 'skill_generation')
WHERE scenes LIKE '%agent-reasoning%'
   OR scenes LIKE '%agent-tool-calling%'
   OR scenes LIKE '%agent-generation%'
   OR scenes LIKE '%skill-generation%';

-- 为所有具有 CHAT 能力且缺少 knowledge_rerank 的 Provider 补齐
UPDATE llm_providers
SET scenes = REPLACE(scenes, ']', ',"knowledge_rerank"]')
WHERE capabilities LIKE '%CHAT%'
  AND scenes NOT LIKE '%knowledge_rerank%'
  AND scenes NOT LIKE '%embedding%';

-- 为所有具有 CHAT 能力且缺少 document_summary 的 Provider 补齐
UPDATE llm_providers
SET scenes = REPLACE(scenes, ']', ',"document_summary"]')
WHERE capabilities LIKE '%CHAT%'
  AND scenes NOT LIKE '%document_summary%'
  AND scenes NOT LIKE '%embedding%';

-- 为所有具有 CHAT 能力且缺少 skill_generation 的 Provider 补齐
UPDATE llm_providers
SET scenes = REPLACE(scenes, ']', ',"skill_generation"]')
WHERE capabilities LIKE '%CHAT%'
  AND scenes NOT LIKE '%skill_generation%'
  AND scenes NOT LIKE '%embedding%';

-- 为所有具有 CHAT 能力且缺少 memory_compression 的 Provider 补齐
UPDATE llm_providers
SET scenes = REPLACE(scenes, ']', ',"memory_compression"]')
WHERE capabilities LIKE '%CHAT%'
  AND scenes NOT LIKE '%memory_compression%'
  AND scenes NOT LIKE '%embedding%';

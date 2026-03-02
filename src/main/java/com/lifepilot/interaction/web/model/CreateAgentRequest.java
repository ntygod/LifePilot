package com.lifepilot.interaction.web.model;

import java.util.List;
import java.util.Map;

/**
 * 创建 Agent 请求。
 *
 * @param name            Agent 名称
 * @param description    描述
 * @param systemPrompt   System Prompt
 * @param modelId        模型 ID
 * @param temperature    温度参数
 * @param maxTokens      最大 Tokens
 * @param knowledgeBaseIds 知识库 ID 列表
 * @param toolIds        工具 ID 列表
 * @param tags           标签列表
 * @param metadata       扩展元数据
 * @author zsg
 * @since 2026-02-28
 */
public record CreateAgentRequest(
        String name,
        String description,
        String systemPrompt,
        String modelId,
        Double temperature,
        Integer maxTokens,
        List<String> knowledgeBaseIds,
        List<String> toolIds,
        List<String> tags,
        Map<String, Object> metadata
) {}

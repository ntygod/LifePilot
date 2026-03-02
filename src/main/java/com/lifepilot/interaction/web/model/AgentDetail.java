package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent 详情信息。
 *
 * @param id                  Agent ID
 * @param name                Agent 名称
 * @param description         Agent 描述
 * @param type                Agent 类型（"default"/"custom"/"workflow"）
 * @param modelId             模型 ID（兼容字段）
 * @param knowledgeBaseCount   关联的知识库数量
 * @param updatedAt           更新时间
 * @param createdAt           创建时间
 * @param status              Agent 状态（"enabled"/"disabled"）
 * @param tags                标签列表
 * @param systemPrompt        System Prompt 内容
 * @param modelConfig         模型配置
 * @param knowledgeBases      关联的知识库列表
 * @param enabledTools        启用的工具ID列表
 * @param metadata            扩展元数据
 * @author zsg
 * @since 2026-02-28
 */
public record AgentDetail(
        String id,
        String name,
        String description,
        String type,
        String modelId,
        int knowledgeBaseCount,
        Instant updatedAt,
        Instant createdAt,
        String status,
        List<String> tags,
        String systemPrompt,
        ModelConfig modelConfig,
        List<KnowledgeBaseInfo> knowledgeBases,
        List<String> enabledTools,
        Map<String, Object> metadata
) {
    /**
     * 模型配置。
     */
    public record ModelConfig(
            String modelId,
            Double temperature,
            Integer maxTokens,
            Double topP
    ) {}

    /**
     * 知识库信息。
     */
    public record KnowledgeBaseInfo(
            String id,
            String name,
            Integer topK,
            Integer maxContextTokens
    ) {}
}

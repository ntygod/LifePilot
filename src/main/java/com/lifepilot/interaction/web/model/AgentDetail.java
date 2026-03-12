package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent 详情信息。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record AgentDetail(
        String id,
        String name,
        String description,
        String type,
        String source,
        String preferredProviderId,
        int knowledgeBaseCount,
        Instant updatedAt,
        Instant createdAt,
        boolean enabled,
        String status,
        List<String> tags,
        String systemPrompt,
        LlmConfig llmConfig,
        List<KnowledgeBaseInfo> knowledgeBases,
        List<String> enabledTools,
        Map<String, Object> metadata
) {
    public record LlmConfig(
            String preferredProviderId,
            Double temperature,
            Integer maxTokens,
            Double topP
    ) {}

    public record KnowledgeBaseInfo(
            String id,
            String name,
            Integer topK,
            Integer maxContextTokens
    ) {}
}

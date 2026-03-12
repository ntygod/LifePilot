package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 创建 Agent 请求。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record CreateAgentRequest(
        String name,
        @Nullable String description,
        @Nullable String systemPrompt,
        @JsonAlias("modelId") @Nullable String preferredProviderId,
        @Nullable Double temperature,
        @Nullable Integer maxTokens,
        @Nullable Double topP,
        @Nullable List<String> knowledgeBaseIds,
        @JsonAlias("enabledTools") @Nullable List<String> toolIds,
        @Nullable List<String> tags,
        @Nullable Map<String, Object> metadata
) {}

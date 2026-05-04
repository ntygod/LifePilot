package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * 会话配置更新请求。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record SessionConfigRequest(
        @JsonAlias("modelId") String preferredProviderId,
        Double temperature,
        Integer maxSteps,
        Integer maxDurationSeconds,
        List<String> knowledgeBaseIds
) {}

package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 消息/产物沉淀到资料库后的标记请求。
 *
 * @author zsg
 * @since 2026-07-07
 */
public record KnowledgeSettlementRequest(
        String knowledgeBaseId,
        String knowledgeBaseName,
        @Nullable String sourceType,
        @Nullable String artifactId,
        @Nullable String fileName
) {
}

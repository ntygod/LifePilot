package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 会话配置更新请求。
 *
 * <p>用于更新会话的配置项，包括模型ID、温度参数、最大Tokens和关联的知识库列表。
 *
 * @param modelId         模型 ID（可选）
 * @param temperature     温度参数（可选）
 * @param maxTokens       最大 Tokens（可选）
 * @param knowledgeBaseIds 关联知识库列表（可选）
 * @author zsg
 * @since 2026-02-28
 */
public record SessionConfigRequest(
        String modelId,
        Double temperature,
        Integer maxTokens,
        List<String> knowledgeBaseIds
) {
}

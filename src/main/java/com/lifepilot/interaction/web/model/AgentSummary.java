package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.List;

/**
 * Agent 摘要信息。
 *
 * @param id                  Agent ID
 * @param name                Agent 名称
 * @param description         Agent 描述
 * @param type                Agent 类型（"default"/"custom"/"workflow"）
 * @param modelId             模型 ID
 * @param knowledgeBaseCount  关联的知识库数量
 * @param updatedAt           更新时间
 * @param createdAt           创建时间
 * @param enabled             是否启用（由 status 字段派生，status="enabled" 时为 true）
 * @param status              Agent 状态（"enabled"/"disabled"）
 * @param tags                标签列表
 * @author zsg
 * @since 2026-02-28
 */
public record AgentSummary(
        String id,
        String name,
        String description,
        String type,
        String modelId,
        int knowledgeBaseCount,
        Instant updatedAt,
        Instant createdAt,
        boolean enabled,
        String status,
        List<String> tags
) {}

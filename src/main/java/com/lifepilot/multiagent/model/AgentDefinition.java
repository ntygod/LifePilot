package com.lifepilot.multiagent.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Agent 完整蓝图 record。
 *
 * <p>包含身份标识、System Prompt、工具白名单、预算约束、模型偏好等字段。
 * 紧凑构造器执行防御性拷贝和非空校验。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@Builder(toBuilder = true)
public record AgentDefinition(
        String id,
        String name,
        String description,
        String systemPrompt,
        List<String> allowedTools,
        boolean canDelegate,
        AgentBudget budget,
        @Nullable String preferredProvider,
        AgentSource source,
        Map<String, String> metadata
) {

    /** 紧凑构造器 — 防御性拷贝 + 校验。 */
    public AgentDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Agent ID 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Agent 名称不能为空");
        if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("System Prompt 不能为空");
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (budget == null) budget = AgentBudget.DEFAULT;
        if (source == null) throw new IllegalArgumentException("Agent 来源不能为空");
    }
}

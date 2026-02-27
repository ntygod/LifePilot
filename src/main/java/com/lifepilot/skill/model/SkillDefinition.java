package com.lifepilot.skill.model;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Skill 定义 — Agent 能力单元的完整蓝图。
 *
 * <p>每个 Skill 通过此 record 描述其 ID、名称、描述、版本、来源、
 * System Prompt、工具白名单、执行策略、记忆访问策略、预算约束和元数据。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
@Builder(toBuilder = true)
public record SkillDefinition(
        String id,
        String name,
        String description,
        String version,
        SkillSource source,
        String systemPrompt,
        List<String> allowedTools,
        ExecutionStrategy execution,
        MemoryAccessPolicy memoryAccess,
        SkillBudget budget,
        Map<String, String> metadata
) {

    /** 紧凑构造器 — 校验 + 防御性拷贝。 */
    public SkillDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Skill ID 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Skill 名称不能为空");
        if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("System Prompt 不能为空");
        allowedTools = List.copyOf(allowedTools);
        metadata = Map.copyOf(metadata);
    }

    /**
     * 返回仅包含 id 和 description 的摘要字符串，用于渐进式发现。
     *
     * @return 格式为 "id: description" 的摘要
     */
    public String toDiscoverySummary() {
        return id + ": " + description;
    }
}

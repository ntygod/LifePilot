package com.lifepilot.skill.model;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Skill 定义 — 程序性知识包的完整蓝图。
 *
 * <p>每个 Skill 通过此 record 描述其 ID、名称、描述、版本、来源、
 * 指令（instructions）、建议工具列表（suggestedTools）和元数据。</p>
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
        String instructions,
        List<String> suggestedTools,
        Map<String, String> metadata
) {

    /** 紧凑构造器 — 校验 + 防御性拷贝。 */
    public SkillDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Skill ID 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Skill 名称不能为空");
        if (instructions == null || instructions.isBlank()) throw new IllegalArgumentException("Skill 指令不能为空");
        suggestedTools = List.copyOf(suggestedTools);
        metadata = Map.copyOf(metadata);
    }

    /**
     * 返回包含 id、name 和 description 的摘要字符串，用于渐进式发现。
     *
     * @return 格式为 "id (name): description" 的摘要
     */
    public String toDiscoverySummary() {
        return id + " (" + name + "): " + description;
    }
}

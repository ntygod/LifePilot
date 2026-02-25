package com.lifepilot.skill.generation;

import java.util.List;

/**
 * Skill 缺口描述。
 *
 * @param confidence     置信度（0.0-1.0）
 * @param suggestedId    建议的 Skill ID
 * @param suggestedName  建议的 Skill 名称
 * @param triggerRequest 触发请求
 * @param suggestedTools 建议的工具列表
 * @param reason         分析原因
 * @author zsg
 * @since 2026-02-25
 */
public record SkillGap(
        double confidence,
        String suggestedId,
        String suggestedName,
        String triggerRequest,
        List<String> suggestedTools,
        String reason
) {
    /** 防御性拷贝。 */
    public SkillGap {
        suggestedTools = List.copyOf(suggestedTools);
    }
}

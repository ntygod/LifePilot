package com.lifepilot.skill.model;

import java.util.List;

/**
 * Skill 激活结果 — 包含注入 Agent 上下文所需的指令和建议工具。
 *
 * <p>由 {@link com.lifepilot.skill.activation.SkillActivator} 在激活成功后返回，
 * 供 ContextAssembler 将 Skill 指令插入 Agent 上下文。</p>
 *
 * @param skillId        Skill ID
 * @param instructions   Skill 指令（注入 Agent 上下文）
 * @param suggestedTools 建议工具列表
 * @author zsg
 * @since 2026-03-07
 */
public record SkillActivation(
        String skillId,
        String instructions,
        List<String> suggestedTools
) {

    /** 紧凑构造器 — 防御性拷贝。 */
    public SkillActivation {
        suggestedTools = List.copyOf(suggestedTools);
    }
}

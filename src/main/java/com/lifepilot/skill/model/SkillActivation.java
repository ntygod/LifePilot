package com.lifepilot.skill.model;

import java.util.List;

/**
 * Skill 激活结果 — 包含注入 Agent 上下文所需的指令和建议工具。
 *
 * <p>由 {@link com.lifepilot.skill.activation.SkillActivator} 在激活成功后返回，
 * 供 ContextAssembler 将 Skill 指令插入 Agent 上下文。</p>
 *
 * @param name           Skill 名称（v2 规范：name 取代旧的 id）
 * @param instructions   Skill 指令（注入 Agent 上下文，已完成占位符替换）
 * @param suggestedTools 建议工具列表
 * @author zsg
 * @since 2026-04-24
 */
public record SkillActivation(
        String name,
        String instructions,
        List<String> suggestedTools
) {

    /** 紧凑构造器 — 防御性拷贝。 */
    public SkillActivation {
        suggestedTools = List.copyOf(suggestedTools);
    }
}

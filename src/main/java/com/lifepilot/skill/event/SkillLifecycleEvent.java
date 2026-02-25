package com.lifepilot.skill.event;

import com.lifepilot.skill.model.SubAgentResult;

/**
 * Skill 生命周期事件 — 穷举激活和停用两种事件类型。
 *
 * <p>通过 Spring {@link org.springframework.context.ApplicationEventPublisher} 发布，
 * 由 {@link com.lifepilot.skill.activation.SkillLifecycleManager} 在 Skill 激活和停用时触发。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public sealed interface SkillLifecycleEvent permits
        SkillLifecycleEvent.Activated,
        SkillLifecycleEvent.Deactivated {

    /**
     * Skill 激活事件。
     *
     * @param skillId Skill ID
     * @param traceId 追踪 ID
     */
    record Activated(String skillId, String traceId) implements SkillLifecycleEvent {}

    /**
     * Skill 停用事件。
     *
     * @param skillId Skill ID
     * @param traceId 追踪 ID
     * @param result  SubAgent 执行结果
     */
    record Deactivated(String skillId, String traceId, SubAgentResult result) implements SkillLifecycleEvent {}
}

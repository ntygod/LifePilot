package com.lifepilot.skill.event;

/**
 * Skill 生命周期事件 — 穷举激活和停用两种事件类型。
 *
 * <p>通过 Spring {@link org.springframework.context.ApplicationEventPublisher} 发布，
 * 由 {@link com.lifepilot.skill.activation.SkillActivator} 在 Skill 激活时触发。</p>
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
     */
    record Activated(String skillId) implements SkillLifecycleEvent {}

    /**
     * Skill 停用事件。
     *
     * @param skillId Skill ID
     */
    record Deactivated(String skillId) implements SkillLifecycleEvent {}
}

package com.lifepilot.skill.event;

import com.lifepilot.skill.model.SkillDefinition;

/**
 * Skill 注册中心事件 — 穷举注册、注销、更新三种事件类型。
 *
 * <p>通过 Spring {@link org.springframework.context.ApplicationEventPublisher} 发布，
 * 由 SkillDisclosureTool 等组件监听处理。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public sealed interface SkillRegistryEvent permits
        SkillRegistryEvent.SkillRegistered,
        SkillRegistryEvent.SkillUnregistered,
        SkillRegistryEvent.SkillUpdated {

    /**
     * Skill 注册事件。
     *
     * @param definition 已注册的 Skill 定义
     */
    record SkillRegistered(SkillDefinition definition) implements SkillRegistryEvent {}

    /**
     * Skill 注销事件。
     *
     * @param skillId 已注销的 Skill ID
     */
    record SkillUnregistered(String skillId) implements SkillRegistryEvent {}

    /**
     * Skill 更新事件。
     *
     * @param oldDefinition 更新前的 Skill 定义
     * @param newDefinition 更新后的 Skill 定义
     */
    record SkillUpdated(SkillDefinition oldDefinition, SkillDefinition newDefinition) implements SkillRegistryEvent {}
}

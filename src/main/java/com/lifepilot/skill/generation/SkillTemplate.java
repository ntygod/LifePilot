package com.lifepilot.skill.generation;

/**
 * Skill 模板 record — 包含模板名称、场景分类和完整 SKILL.md 内容。
 *
 * @author zsg
 * @since 2026-03-18
 */
public record SkillTemplate(
        String name,
        SkillTemplateLibrary.TemplateScene scene,
        String markdownContent
) {}

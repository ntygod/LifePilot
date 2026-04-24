package com.lifepilot.skill.spec;

/**
 * SKILL.md frontmatter 的结构化表示。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillFrontmatter(
        String name,
        String description,
        String version,
        SkillZhiweiMeta zhiweiMeta
) {
    public SkillFrontmatter {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description 不能为空");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
        zhiweiMeta = zhiweiMeta == null ? SkillZhiweiMeta.empty() : zhiweiMeta;
    }
}

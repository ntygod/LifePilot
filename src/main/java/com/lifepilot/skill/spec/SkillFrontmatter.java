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
        name = name == null ? null : name.strip();
        description = description == null ? null : description.strip();
        version = version == null ? null : version.strip();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("SKILL.md frontmatter name 字段不能为空");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("SKILL.md frontmatter description 字段不能为空");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("SKILL.md frontmatter version 字段不能为空");
        zhiweiMeta = zhiweiMeta == null ? SkillZhiweiMeta.empty() : zhiweiMeta;
    }
}

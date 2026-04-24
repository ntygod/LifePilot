package com.lifepilot.skill.spec;

import java.util.List;

/**
 * SKILL.md frontmatter 下 `metadata.zhiwei` 块的结构化视图。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillZhiweiMeta(
        List<String> suggestedTools,
        List<String> tags,
        String category,
        SkillPriority priority,
        SkillRequires requires
) {
    public static SkillZhiweiMeta empty() {
        return new SkillZhiweiMeta(List.of(), List.of(), null, SkillPriority.NORMAL, SkillRequires.empty());
    }

    public SkillZhiweiMeta {
        suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
        tags = tags == null ? List.of() : List.copyOf(tags);
        priority = priority == null ? SkillPriority.NORMAL : priority;
        requires = requires == null ? SkillRequires.empty() : requires;
    }
}

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

    /** category 字段长度上限，防止被写入超长字符串污染 Skill catalog XML 输出。 */
    private static final int CATEGORY_MAX_LENGTH = 64;

    public SkillZhiweiMeta {
        suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
        tags = tags == null ? List.of() : List.copyOf(tags);
        category = (category == null || category.isBlank()) ? null : category.strip();
        if (category != null && category.length() > CATEGORY_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "category 长度超过 " + CATEGORY_MAX_LENGTH + " 限制（当前 " + category.length() + "）");
        }
        priority = priority == null ? SkillPriority.NORMAL : priority;
        requires = requires == null ? SkillRequires.empty() : requires;
    }
}

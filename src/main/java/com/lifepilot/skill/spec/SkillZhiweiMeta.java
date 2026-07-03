package com.lifepilot.skill.spec;

import java.util.List;

/**
 * SKILL.md frontmatter 下 {@code metadata.zhiwei} 块的结构化视图。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillZhiweiMeta(
        List<String> suggestedTools,
        List<String> tags,
        List<String> outputs,
        SkillRequires requires
) {
    public static SkillZhiweiMeta empty() {
        return new SkillZhiweiMeta(List.of(), List.of(), List.of(), SkillRequires.empty());
    }

    public SkillZhiweiMeta {
        suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
        tags = tags == null ? List.of() : List.copyOf(tags);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        requires = requires == null ? SkillRequires.empty() : requires;
    }
}

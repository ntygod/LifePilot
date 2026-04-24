package com.lifepilot.skill.spec;

import java.util.List;

/**
 * Skill 运行期依赖声明。加载期由 SkillRequirementGate 硬过滤。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillRequires(
        List<String> bins,
        List<String> env,
        List<String> os,
        List<String> tools
) {
    public static SkillRequires empty() {
        return new SkillRequires(List.of(), List.of(), List.of(), List.of());
    }

    public SkillRequires {
        bins = bins == null ? List.of() : List.copyOf(bins);
        env = env == null ? List.of() : List.copyOf(env);
        os = os == null ? List.of() : List.copyOf(os);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}

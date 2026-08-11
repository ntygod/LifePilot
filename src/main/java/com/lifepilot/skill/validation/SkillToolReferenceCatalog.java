package com.lifepilot.skill.validation;

import java.util.Set;

/**
 * Skill 元数据可引用的稳定工具 ID 目录。
 *
 * <p>{@code suggested_tools} 是触发与展示线索，不等同于运行期硬依赖。
 * 启动期 BUILTIN Skill 安装早于部分工具注册完成，因此这里维护一份稳定能力面目录，
 * 让 Skill 元数据能引用这些 canonical ID，而真正执行前仍以
 * {@link com.lifepilot.tool.registry.DynamicToolRegistry} 为准。</p>
 *
 * @author zsg
 * @since 2026-07-06
 */
public final class SkillToolReferenceCatalog {

    private static final Set<String> CANONICAL_TOOL_IDS = Set.of(
            "status",
            "tool.search",
            "skill.load",
            "memory",
            "web.search",
            "web.fetch",
            "browser",
            "file.read",
            "file.write",
            "file.manage",
            "file.attach",
            "file.history",
            "shell.exec",
            "shell.process",
            "code",
            "git.query",
            "git.mutate",
            "transcript.search",
            "transcript.get",
            "cron",
            "notify",
            "ui.render",
            "channel.feishu"
    );

    private SkillToolReferenceCatalog() {
    }

    /**
     * 判断工具 ID 是否属于 Skill 元数据可声明的稳定能力面。
     *
     * @param toolId 工具 ID
     * @return 是否为 canonical tool id
     */
    public static boolean isCanonicalToolId(String toolId) {
        return toolId != null && CANONICAL_TOOL_IDS.contains(toolId);
    }

    /**
     * 返回全部 canonical ID，供规范测试与文档生成复用。
     *
     * @return canonical ID 集合
     */
    public static Set<String> canonicalToolIds() {
        return CANONICAL_TOOL_IDS;
    }
}

package com.lifepilot.tool.model;

/**
 * 工具分层级别 — 决定工具在何时暴露给 LLM。
 *
 * <p>三级分层策略：
 * <ul>
 *   <li>{@link #CORE} — 核心工具，始终暴露给 LLM，无需激活</li>
 *   <li>{@link #SKILL} — 技能工具，激活对应 Skill 后暴露</li>
 *   <li>{@link #DISCOVERY} — 发现工具，仅通过 find_tool 语义检索后按需暴露</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-31
 */
public enum ToolTier {

    /** 核心工具 — 始终暴露给 LLM，无需激活。 */
    CORE,

    /** 技能工具 — 激活对应 Skill 后暴露。 */
    SKILL,

    /** 发现工具 — 仅通过 find_tool 语义检索后按需暴露。 */
    DISCOVERY
}

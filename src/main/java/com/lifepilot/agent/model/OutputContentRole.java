package com.lifepilot.agent.model;

/**
 * 终态输出文本角色。
 *
 * <p>用于区分真正的最终正文、内部进度提示、挂起追问和阻塞说明，
 * 避免同一个 content 字段在前端被误当成可展示的最终答案。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public enum OutputContentRole {
    FINAL,
    PROGRESS,
    SUSPEND_PROMPT,
    BLOCKED
}

package com.lifepilot.tool.model;

/**
 * 工具执行状态枚举。
 *
 * <p>替代 {@link ToolResult} 中的布尔 {@code ok} 字段，
 * 支持表达中间状态（部分成功、限流）。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public enum ToolResultStatus {

    /** 执行成功。 */
    SUCCESS,

    /** 执行失败。 */
    ERROR,

    /** 部分成功 — data 包含已成功部分，error 包含失败描述。 */
    PARTIAL_SUCCESS,

    /** 限流 — 建议等待 retryAfterMs 后重试。 */
    RATE_LIMITED;

    /**
     * 是否成功（仅 {@code SUCCESS} 返回 {@code true}）。
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * 是否为终态（不可重试）。
     *
     * <p>{@code SUCCESS} 和 {@code ERROR} 为终态；
     * {@code PARTIAL_SUCCESS} 和 {@code RATE_LIMITED} 为非终态。</p>
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == ERROR;
    }
}

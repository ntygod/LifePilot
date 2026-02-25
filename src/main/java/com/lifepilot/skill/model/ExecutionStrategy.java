package com.lifepilot.skill.model;

/**
 * Skill 执行策略。
 *
 * @author zsg
 * @since 2026-07-28
 */
public record ExecutionStrategy(
        int maxSteps,
        int timeoutSeconds,
        boolean requireConfirmation,
        RetryPolicy retryPolicy,
        ConfirmationMode confirmationMode
) {

    /** 默认执行策略：10 步、120 秒超时、无确认、默认重试。 */
    public static final ExecutionStrategy DEFAULT = new ExecutionStrategy(
            10, 120, false, RetryPolicy.DEFAULT, ConfirmationMode.NONE);

    /** 紧凑构造器 — 值范围校验。 */
    public ExecutionStrategy {
        if (maxSteps < 1 || maxSteps > 50) {
            throw new IllegalArgumentException("maxSteps 必须在 1-50 之间");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalArgumentException("timeoutSeconds 必须在 1-600 之间");
        }
    }

    /** 重试策略。 */
    public enum RetryPolicy { NONE, DEFAULT }

    /** 确认模式。 */
    public enum ConfirmationMode { NONE, FIRST_RUN, ALWAYS }
}

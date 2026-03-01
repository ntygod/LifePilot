package com.lifepilot.memory.procedural;

import java.time.Instant;

/**
 * 策略模式 — 特定情境下推荐的高层次决策策略。
 *
 * <p>记录在某种情境（{@code situation}）下推荐的行动方案，
 * 通过 {@code successRate} 和 {@code applicationCount} 追踪策略的有效性。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public record StrategyPattern(
        String patternId,
        String situation,
        String recommendedAction,
        float successRate,
        int applicationCount,
        Instant createdAt
) {}

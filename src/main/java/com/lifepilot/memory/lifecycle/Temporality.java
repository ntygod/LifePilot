package com.lifepilot.memory.lifecycle;

/**
 * 实体时效性 — 决定默认过期策略。
 *
 * <ul>
 *   <li>{@link #EPHEMERAL}：极短期 / 一次性（默认 1 小时过期）。</li>
 *   <li>{@link #SHORT_TERM}：短期（默认 7 天过期）。</li>
 *   <li>{@link #PERSISTENT}：长期持有（不自动过期）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum Temporality {
    EPHEMERAL,
    SHORT_TERM,
    PERSISTENT
}

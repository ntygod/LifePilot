package com.lifepilot.agent.proactive.model;

import java.time.Instant;

/**
 * 频率状态条目 — FrequencyStateManager 的运行时缓存和持久化单元。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record FrequencyStateEntry(
        FrequencyState state,
        int consecutiveIgnoreCount,
        Instant lastNotifiedAt
) {

    /** 创建初始状态条目。 */
    public static FrequencyStateEntry initial() {
        return new FrequencyStateEntry(FrequencyState.NORMAL, 0, Instant.EPOCH);
    }
}

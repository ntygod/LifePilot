package com.lifepilot.agent.model;

import org.springframework.lang.Nullable;

/**
 * 已执行步骤记录 record。
 *
 * @author zsg
 * @since 2026-07-20
 */
public record StepRecord(
        @Nullable String toolId,
        boolean success,
        String output,
        boolean blocked,
        int tokensUsed,
        long latencyMs
) {
}

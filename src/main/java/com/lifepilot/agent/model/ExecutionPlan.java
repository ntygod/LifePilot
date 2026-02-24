package com.lifepilot.agent.model;

import java.util.List;

/**
 * 执行计划 record。
 *
 * @author zsg
 * @since 2026-07-20
 */
public record ExecutionPlan(
        List<PlanStep> steps,
        int estimatedTokens,
        String rationale
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public ExecutionPlan {
        steps = List.copyOf(steps);
    }
}

package com.lifepilot.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 计划步骤 record。
 *
 * @author zsg
 * @since 2026-07-20
 */
public record PlanStep(
        int index,
        String toolId,
        Map<String, Object> params,
        List<Integer> dependsOn,
        String description
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public PlanStep {
        params = Map.copyOf(params);
        dependsOn = List.copyOf(dependsOn);
    }
}

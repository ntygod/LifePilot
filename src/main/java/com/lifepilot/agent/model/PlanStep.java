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
        // 允许 LLM 省略 params/dependsOn 字段，自动降级为空集合，避免 NPE
        params = (params == null || params.isEmpty()) ? Map.of() : Map.copyOf(params);
        dependsOn = (dependsOn == null || dependsOn.isEmpty()) ? List.of() : List.copyOf(dependsOn);
    }
}

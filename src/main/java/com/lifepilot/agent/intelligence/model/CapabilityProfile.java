package com.lifepilot.agent.intelligence.model;

import java.util.List;
import java.util.Map;

/**
 * 能力画像 — 某任务类型的能力评估结果。
 *
 * @author zsg
 * @since 2026-06-01
 */
public record CapabilityProfile(
    String taskType,
    float overallScore,
    int historicalAttempts,
    float successRate,
    Map<String, Float> toolProficiency,
    List<String> knownPatterns,
    List<String> knownRisks
) {
    public boolean isCapable() {
        return overallScore >= 0.5f;
    }

    public static CapabilityProfile unknown(String taskType) {
        return new CapabilityProfile(taskType, 0.5f, 0, 0.0f, Map.of(), List.of(), List.of());
    }
}

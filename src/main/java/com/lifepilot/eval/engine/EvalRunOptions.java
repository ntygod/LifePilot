package com.lifepilot.eval.engine;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 评估运行选项 — 控制批量评估的行为。
 *
 * @param scenarioIds    指定场景 ID 列表
 * @param tag            按标签过滤
 * @param baselineRunId  对比基线运行 ID
 * @param smokeTestOnly  只运行标记为 smoke 的场景
 * @param offlineReeval  离线重评（跳过 Agent 执行，使用已有 trace）
 * @param labels         自定义标签
 * @author zsg
 * @since 2026-03-22
 */
public record EvalRunOptions(
        @Nullable List<String> scenarioIds,
        @Nullable String tag,
        @Nullable String baselineRunId,
        boolean smokeTestOnly,
        boolean offlineReeval,
        @Nullable Map<String, String> labels
) {
    public EvalRunOptions {
        labels = labels != null ? Map.copyOf(labels) : Map.of();
    }

    /**
     * 从 EvalRunRequest 构建默认选项。
     */
    public static EvalRunOptions defaults() {
        return new EvalRunOptions(null, null, null, false, false, Map.of());
    }
}

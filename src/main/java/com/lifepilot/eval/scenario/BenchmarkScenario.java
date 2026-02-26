package com.lifepilot.eval.scenario;

import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Benchmark 场景 — 声明式评估用例。
 *
 * @param id                    场景唯一 ID
 * @param name                  场景名称
 * @param userInput             用户输入消息
 * @param expectedToolCalls     期望工具调用序列（toolId 列表）
 * @param expectedOutputPattern 期望最终输出的正则模式
 * @param dimensionWeights      评估维度权重（5 维，和为 1.0）
 * @param timeoutSeconds        超时时间（秒）
 * @param mockToolResponses     Mock 工具响应（toolId → 响应 JSON）
 * @param initialContext        初始上下文设置
 * @param tags                  标签（用于过滤）
 * @param llmJudgeCriteria      LLM 评判标准（可选）
 * @param expectedTokenBudget   期望 Token 预算上限
 * @param expectedStepCount     期望步骤数
 * @author zsg
 * @since 2026-08-01
 */
@Builder(toBuilder = true)
public record BenchmarkScenario(
        String id,
        String name,
        String userInput,
        List<String> expectedToolCalls,
        @Nullable String expectedOutputPattern,
        Map<String, Double> dimensionWeights,
        int timeoutSeconds,
        @Nullable Map<String, String> mockToolResponses,
        @Nullable Map<String, String> initialContext,
        List<String> tags,
        @Nullable String llmJudgeCriteria,
        int expectedTokenBudget,
        int expectedStepCount
) {
    public BenchmarkScenario {
        Objects.requireNonNull(id, "场景 ID 不能为空");
        Objects.requireNonNull(name, "场景名称不能为空");
        Objects.requireNonNull(userInput, "用户输入不能为空");
        expectedToolCalls = expectedToolCalls != null ? List.copyOf(expectedToolCalls) : List.of();
        dimensionWeights = dimensionWeights != null ? Map.copyOf(dimensionWeights) : Map.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
        mockToolResponses = mockToolResponses != null ? Map.copyOf(mockToolResponses) : null;
        initialContext = initialContext != null ? Map.copyOf(initialContext) : null;
    }
}

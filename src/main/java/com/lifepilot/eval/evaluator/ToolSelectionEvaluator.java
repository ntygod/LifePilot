package com.lifepilot.eval.evaluator;

import com.lifepilot.agent.trace.TraceStep;
import com.lifepilot.eval.scenario.BenchmarkScenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具选择正确性评估器。
 *
 * <p>比较实际工具调用序列与期望序列，计算 LCS（最长公共子序列）相似度。
 * 评分 = LCS 长度 / max(期望序列长度, 实际序列长度)。
 * 若两者均为空，评分为 1.0。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public final class ToolSelectionEvaluator implements DimensionEvaluator {

    private static final String DIMENSION_NAME = "toolSelection";

    @Override
    public String dimensionName() {
        return DIMENSION_NAME;
    }

    @Override
    public DimensionScore evaluate(List<TraceStep> steps, BenchmarkScenario scenario) {
        // 提取实际工具调用序列（toolId 非 null 的步骤）
        List<String> actualToolCalls = steps.stream()
                .filter(s -> s.toolId() != null)
                .map(TraceStep::toolId)
                .toList();

        List<String> expectedToolCalls = scenario.expectedToolCalls();

        // 两者均为空，评分 1.0
        if (expectedToolCalls.isEmpty() && actualToolCalls.isEmpty()) {
            return new DimensionScore(DIMENSION_NAME, 1.0, List.of(), List.of());
        }

        int lcsLength = computeLcs(expectedToolCalls, actualToolCalls);
        int maxLength = Math.max(expectedToolCalls.size(), actualToolCalls.size());
        double score = (double) lcsLength / maxLength;

        // 构建违规项和建议
        var violations = new ArrayList<String>();
        var suggestions = new ArrayList<String>();

        if (score < 1.0) {
            violations.add("工具调用序列不完全匹配: 期望=%s, 实际=%s, LCS=%d"
                    .formatted(expectedToolCalls, actualToolCalls, lcsLength));
        }
        if (actualToolCalls.size() > expectedToolCalls.size()) {
            suggestions.add("实际工具调用数(%d)多于期望(%d)，可能存在冗余调用"
                    .formatted(actualToolCalls.size(), expectedToolCalls.size()));
        }
        if (actualToolCalls.size() < expectedToolCalls.size()) {
            suggestions.add("实际工具调用数(%d)少于期望(%d)，可能遗漏了必要的工具调用"
                    .formatted(actualToolCalls.size(), expectedToolCalls.size()));
        }

        return new DimensionScore(DIMENSION_NAME, score,
                List.copyOf(violations), List.copyOf(suggestions));
    }

    /**
     * 计算两个字符串列表的最长公共子序列长度（动态规划）。
     *
     * @param a 序列 A
     * @param b 序列 B
     * @return LCS 长度
     */
    static int computeLcs(List<String> a, List<String> b) {
        int m = a.size();
        int n = b.size();
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[m][n];
    }
}

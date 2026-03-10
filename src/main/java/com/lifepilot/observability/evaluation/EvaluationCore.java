package com.lifepilot.observability.evaluation;

import com.lifepilot.observability.trace.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 共享五维评估核心 — 统一 eval 和 observability 的轨迹评估逻辑。
 *
 * <p>接受 {@link TraceStep} 列表和 {@link EvaluationConfig} 配置，
 * 返回包含五个维度评分的 {@link EvaluationResult}。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class EvaluationCore {

    private static final Logger log = LoggerFactory.getLogger(EvaluationCore.class);

    /**
     * 执行五维评估。
     *
     * @param steps   轨迹步骤列表
     * @param config  评估配置参数
     * @return 评估结果
     */
    public EvaluationResult evaluate(List<TraceStep> steps, EvaluationConfig config) {
        return evaluate(steps, config, "");
    }

    /**
     * 执行五维评估（带 traceId）。
     *
     * @param steps   轨迹步骤列表
     * @param config  评估配置参数
     * @param traceId 追踪 ID
     * @return 评估结果
     */
    public EvaluationResult evaluate(List<TraceStep> steps, EvaluationConfig config, String traceId) {
        if (steps == null || steps.isEmpty()) {
            return defaultResult(traceId);
        }

        try {
            var violations = new ArrayList<String>();
            var suggestions = new ArrayList<String>();

            double toolSelection = evaluateToolSelection(steps, config, violations, suggestions);
            double parameterValidity = evaluateParameterValidity(steps, violations, suggestions);
            double stepEfficiency = evaluateStepEfficiency(steps, config, violations, suggestions);
            double policyCompliance = evaluatePolicyCompliance(steps, violations, suggestions);
            double tokenEfficiency = evaluateTokenEfficiency(steps, config, violations, suggestions);

            double overall = toolSelection * config.toolSelectionWeight()
                    + parameterValidity * config.parameterValidityWeight()
                    + stepEfficiency * config.stepEfficiencyWeight()
                    + policyCompliance * config.policyComplianceWeight()
                    + tokenEfficiency * config.tokenEfficiencyWeight();

            // 计算实际 Token 消耗
            int actualTokens = steps.stream()
                    .filter(s -> s instanceof LlmCallStep)
                    .mapToInt(s -> ((LlmCallStep) s).inputTokens() + ((LlmCallStep) s).outputTokens())
                    .sum();

            return new EvaluationResult(
                    traceId,
                    Instant.now(),
                    toolSelection,
                    parameterValidity,
                    stepEfficiency,
                    policyCompliance,
                    tokenEfficiency,
                    overall,
                    steps.size(),
                    actualTokens,
                    violations,
                    suggestions
            );
        } catch (Exception e) {
            log.error("五维评估异常，返回默认评分: traceId={}, error={}", traceId, e.getMessage());
            return defaultResult(traceId);
        }
    }

    /**
     * 工具选择正确性评估。
     *
     * <p>当 expectedToolCalls 非空时，使用 LCS 算法计算期望与实际工具调用序列的匹配度；
     * 否则基于失败率和重复率评估。</p>
     */
    private double evaluateToolSelection(List<TraceStep> steps, EvaluationConfig config,
                                          List<String> violations, List<String> suggestions) {
        var toolCalls = steps.stream()
                .filter(s -> s instanceof ToolCallStep)
                .map(s -> (ToolCallStep) s)
                .toList();

        if (toolCalls.isEmpty()) {
            return 1.0;
        }

        // 如果有期望工具调用序列，使用 LCS 算法
        if (!config.expectedToolCalls().isEmpty()) {
            List<String> actual = toolCalls.stream().map(ToolCallStep::toolId).toList();
            int lcsLen = lcs(config.expectedToolCalls(), actual);
            int maxLen = Math.max(config.expectedToolCalls().size(), actual.size());
            double score = maxLen > 0 ? (double) lcsLen / maxLen : 1.0;
            if (score < 0.5) {
                violations.add("工具调用序列与期望不匹配: expected=%s, actual=%s"
                        .formatted(config.expectedToolCalls(), actual));
            }
            return Math.max(0.0, Math.min(1.0, score));
        }

        // 无期望序列时，基于失败率和重复率评估
        long failedCalls = toolCalls.stream().filter(t -> !t.success()).count();
        double failureRatio = (double) failedCalls / toolCalls.size();

        int duplicates = 0;
        for (int i = 1; i < toolCalls.size(); i++) {
            if (toolCalls.get(i).toolId().equals(toolCalls.get(i - 1).toolId())
                    && toolCalls.get(i).toolAction().equals(toolCalls.get(i - 1).toolAction())) {
                duplicates++;
            }
        }
        double duplicateRatio = (double) duplicates / toolCalls.size();

        if (failureRatio > 0.3) {
            violations.add("工具调用失败率过高: %.1f%%".formatted(failureRatio * 100));
        }
        if (duplicateRatio > 0.2) {
            suggestions.add("存在重复工具调用，建议优化调用策略");
        }

        return Math.max(0.0, Math.min(1.0, 1.0 - failureRatio * 0.6 - duplicateRatio * 0.4));
    }

    /**
     * 参数合法性评估 — 基于工具调用成功率。
     */
    private double evaluateParameterValidity(List<TraceStep> steps,
                                              List<String> violations, List<String> suggestions) {
        var toolCalls = steps.stream()
                .filter(s -> s instanceof ToolCallStep)
                .map(s -> (ToolCallStep) s)
                .toList();

        if (toolCalls.isEmpty()) {
            return 1.0;
        }

        long successCalls = toolCalls.stream().filter(ToolCallStep::success).count();
        double successRate = (double) successCalls / toolCalls.size();

        if (successRate < 0.7) {
            violations.add("工具调用成功率低: %.1f%%".formatted(successRate * 100));
        }

        return Math.max(0.0, Math.min(1.0, successRate));
    }

    /**
     * 步骤效率评估。
     *
     * <p>当 expectedStepCount > 0 时，使用 min(expected/actual, 1.0) 计算；
     * 否则使用内置启发式规则。</p>
     */
    private double evaluateStepEfficiency(List<TraceStep> steps, EvaluationConfig config,
                                           List<String> violations, List<String> suggestions) {
        int totalSteps = steps.size();
        if (totalSteps == 0) {
            return 1.0;
        }

        // 如果有期望步骤数，使用比值计算
        if (config.expectedStepCount() > 0) {
            double score = Math.min((double) config.expectedStepCount() / totalSteps, 1.0);
            if (score < 0.5) {
                violations.add("步骤数超出期望: actual=%d, expected=%d".formatted(totalSteps, config.expectedStepCount()));
            }
            return Math.max(0.0, score);
        }

        // 无期望值时使用启发式规则
        long llmCalls = steps.stream().filter(s -> s instanceof LlmCallStep).count();
        long toolCalls = steps.stream().filter(s -> s instanceof ToolCallStep).count();

        if (llmCalls > 0 && toolCalls > llmCalls * 3) {
            violations.add("工具调用次数过多: toolCalls=%d, llmCalls=%d".formatted(toolCalls, llmCalls));
            return Math.max(0.0, 1.0 - (double) (toolCalls - llmCalls * 3) / totalSteps);
        }

        if (totalSteps <= 10) {
            return 1.0;
        } else if (totalSteps <= 20) {
            return 0.8;
        } else {
            suggestions.add("步骤数较多（%d），建议优化执行策略".formatted(totalSteps));
            return Math.max(0.3, 1.0 - (totalSteps - 10) * 0.03);
        }
    }

    /**
     * 策略合规性评估 — 基于护栏拦截比例。
     */
    private double evaluatePolicyCompliance(List<TraceStep> steps,
                                             List<String> violations, List<String> suggestions) {
        var guardrailSteps = steps.stream()
                .filter(s -> s instanceof GuardrailStep)
                .map(s -> (GuardrailStep) s)
                .toList();

        if (guardrailSteps.isEmpty()) {
            return 1.0;
        }

        long blockedCount = guardrailSteps.stream().filter(g -> !g.passed()).count();
        if (blockedCount == 0) {
            return 1.0;
        }

        double blockRatio = (double) blockedCount / guardrailSteps.size();
        violations.add("护栏拦截 %d 次（共 %d 次检查）".formatted(blockedCount, guardrailSteps.size()));

        return Math.max(0.0, 1.0 - blockRatio);
    }

    /**
     * Token 效率评估。
     *
     * <p>当 expectedTokenBudget > 0 时，使用 min(expected/actual, 1.0) 计算；
     * 否则使用内置启发式规则。</p>
     */
    private double evaluateTokenEfficiency(List<TraceStep> steps, EvaluationConfig config,
                                            List<String> violations, List<String> suggestions) {
        int totalTokens = steps.stream()
                .filter(s -> s instanceof LlmCallStep)
                .mapToInt(s -> ((LlmCallStep) s).inputTokens() + ((LlmCallStep) s).outputTokens())
                .sum();

        if (totalTokens == 0) {
            return 1.0;
        }

        // 如果有期望 Token 预算，使用比值计算
        if (config.expectedTokenBudget() > 0) {
            double score = Math.min((double) config.expectedTokenBudget() / totalTokens, 1.0);
            if (score < 0.5) {
                violations.add("Token 消耗超出预算: actual=%d, budget=%d".formatted(totalTokens, config.expectedTokenBudget()));
            }
            return Math.max(0.0, score);
        }

        // 无期望值时使用启发式规则
        int expectedTokens = steps.size() * 500;
        if (expectedTokens == 0) {
            expectedTokens = 1000;
        }

        double ratio = (double) totalTokens / expectedTokens;
        if (ratio <= 1.5) {
            return 1.0;
        } else if (ratio <= 3.0) {
            suggestions.add("Token 消耗偏高: actual=%d, expected=%d".formatted(totalTokens, expectedTokens));
            return 0.7;
        } else {
            violations.add("Token 消耗过高: actual=%d, expected=%d".formatted(totalTokens, expectedTokens));
            return Math.max(0.2, 1.0 / ratio);
        }
    }

    /**
     * LCS（最长公共子序列）算法 — 用于工具调用序列匹配。
     */
    private int lcs(List<String> a, List<String> b) {
        int m = a.size(), n = b.size();
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

    /**
     * 默认评估结果 — 空步骤列表或异常时返回。
     */
    private EvaluationResult defaultResult(String traceId) {
        return new EvaluationResult(
                traceId,
                Instant.now(),
                0.5, 0.5, 0.5, 0.5, 0.5,
                0.5,
                0, 0,
                List.of(),
                List.of()
        );
    }
}

package com.lifepilot.observability.evaluation;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹评估引擎 — 对 Agent 执行轨迹进行五维规则评估。
 *
 * <p>五维评估逻辑：
 * <ul>
 *   <li>工具选择正确性：失败/重复调用降分</li>
 *   <li>参数合法性：工具调用成功率</li>
 *   <li>步骤效率：步骤数比率</li>
 *   <li>策略合规性：护栏拦截降分</li>
 *   <li>Token 效率：Token 消耗比率</li>
 * </ul>
 *
 * <p>综合评分 = 各维度评分 × 对应权重之和（权重从配置读取）。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TrajectoryEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryEvaluator.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObservabilityProperties.Evaluation evalConfig;

    public TrajectoryEvaluator(JdbcTemplate jdbcTemplate, ObservabilityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.evalConfig = properties.getEvaluation();
    }

    /**
     * 在线评估 — 评估并持久化到 evaluation_results 表。
     *
     * @param trace 完整的追踪记录
     * @return 评估结果
     */
    public EvaluationResult evaluateOnline(TraceRecord trace) {
        var result = evaluate(trace);
        persistResult(result);
        return result;
    }

    /**
     * 离线评估 — 评估但不持久化（用于历史重评估）。
     *
     * @param trace 完整的追踪记录
     * @return 评估结果
     */
    public EvaluationResult evaluateOffline(TraceRecord trace) {
        return evaluate(trace);
    }

    /**
     * 执行五维评估。
     */
    private EvaluationResult evaluate(TraceRecord trace) {
        try {
            var violations = new ArrayList<String>();
            var suggestions = new ArrayList<String>();

            double toolSelection = evaluateToolSelection(trace, violations, suggestions);
            double parameterValidity = evaluateParameterValidity(trace, violations, suggestions);
            double stepEfficiency = evaluateStepEfficiency(trace, violations, suggestions);
            double policyCompliance = evaluatePolicyCompliance(trace, violations, suggestions);
            double tokenEfficiency = evaluateTokenEfficiency(trace, violations, suggestions);

            double overall = toolSelection * evalConfig.getToolSelectionWeight()
                    + parameterValidity * evalConfig.getParameterValidityWeight()
                    + stepEfficiency * evalConfig.getStepEfficiencyWeight()
                    + policyCompliance * evalConfig.getPolicyComplianceWeight()
                    + tokenEfficiency * evalConfig.getTokenEfficiencyWeight();

            return new EvaluationResult(
                    trace.traceId(),
                    Instant.now(),
                    toolSelection,
                    parameterValidity,
                    stepEfficiency,
                    policyCompliance,
                    tokenEfficiency,
                    overall,
                    trace.totalSteps(),
                    trace.inputTokens() + trace.outputTokens(),
                    violations,
                    suggestions
            );
        } catch (Exception e) {
            log.error("轨迹评估异常，返回默认评分: traceId={}, error={}", trace.traceId(), e.getMessage());
            return defaultResult(trace);
        }
    }

    /**
     * 评估工具选择正确性 — 失败调用和重复调用降分。
     */
    private double evaluateToolSelection(TraceRecord trace, List<String> violations, List<String> suggestions) {
        var toolCalls = trace.steps().stream()
                .filter(s -> s instanceof ToolCallStep)
                .map(s -> (ToolCallStep) s)
                .toList();

        if (toolCalls.isEmpty()) {
            return 1.0;
        }

        long failedCalls = toolCalls.stream().filter(t -> !t.success()).count();
        double failureRatio = (double) failedCalls / toolCalls.size();

        // 检查重复调用（相同 toolId + toolAction 连续出现）
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

        return Math.max(0.0, 1.0 - failureRatio * 0.6 - duplicateRatio * 0.4);
    }

    /**
     * 评估参数合法性 — 工具调用成功率。
     */
    private double evaluateParameterValidity(TraceRecord trace, List<String> violations, List<String> suggestions) {
        var toolCalls = trace.steps().stream()
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

        return successRate;
    }

    /**
     * 评估步骤效率 — 基于步骤数量评估。
     */
    private double evaluateStepEfficiency(TraceRecord trace, List<String> violations, List<String> suggestions) {
        int totalSteps = trace.totalSteps();
        if (totalSteps == 0) {
            return 1.0;
        }

        // 基准：每次 LLM 调用对应一个有效步骤，理想步骤数 = LLM 调用数
        long llmCalls = trace.steps().stream().filter(s -> s instanceof LlmCallStep).count();
        long toolCalls = trace.steps().stream().filter(s -> s instanceof ToolCallStep).count();

        // 如果工具调用远多于 LLM 调用，说明可能存在冗余
        if (llmCalls > 0 && toolCalls > llmCalls * 3) {
            violations.add("工具调用次数过多: toolCalls=%d, llmCalls=%d".formatted(toolCalls, llmCalls));
            return Math.max(0.0, 1.0 - (double) (toolCalls - llmCalls * 3) / totalSteps);
        }

        // 步骤数在合理范围内
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
     * 评估策略合规性 — 护栏拦截降分。
     */
    private double evaluatePolicyCompliance(TraceRecord trace, List<String> violations, List<String> suggestions) {
        var guardrailSteps = trace.steps().stream()
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
     * 评估 Token 效率 — 基于 Token 消耗评估。
     */
    private double evaluateTokenEfficiency(TraceRecord trace, List<String> violations, List<String> suggestions) {
        int totalTokens = trace.inputTokens() + trace.outputTokens();
        if (totalTokens == 0) {
            return 1.0;
        }

        // 基准：每个步骤平均消耗 500 Token 为合理
        int expectedTokens = trace.totalSteps() * 500;
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
     * 持久化评估结果到 evaluation_results 表。
     */
    private void persistResult(EvaluationResult result) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO evaluation_results (trace_id, evaluated_at,
                        tool_selection_score, parameter_validity_score, step_efficiency_score,
                        policy_compliance_score, token_efficiency_score, overall_score,
                        actual_steps, actual_tokens, violations_json, suggestions_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    result.traceId(),
                    result.evaluatedAt().toString(),
                    result.toolSelectionScore(),
                    result.parameterValidityScore(),
                    result.stepEfficiencyScore(),
                    result.policyComplianceScore(),
                    result.tokenEfficiencyScore(),
                    result.overallScore(),
                    result.actualSteps(),
                    result.actualTokens(),
                    String.join(",", result.violations()),
                    String.join(",", result.suggestions()),
                    Instant.now().toString());
        } catch (Exception e) {
            log.warn("评估结果持久化失败: traceId={}, error={}", result.traceId(), e.getMessage());
        }
    }

    /**
     * 返回默认评估结果（所有维度 0.5）。
     */
    private EvaluationResult defaultResult(TraceRecord trace) {
        return new EvaluationResult(
                trace.traceId(),
                Instant.now(),
                0.5, 0.5, 0.5, 0.5, 0.5, 0.5,
                trace.totalSteps(),
                trace.inputTokens() + trace.outputTokens(),
                List.of("评估异常，使用默认评分"),
                List.of()
        );
    }
}

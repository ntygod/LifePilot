package com.lifepilot.observability.evaluation;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.TraceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 轨迹评估引擎 — 对 Agent 执行轨迹进行五维规则评估。
 *
 * <p>委托 {@link EvaluationCore} 执行五维评估逻辑，
 * 自身负责在线/离线模式切换和结果持久化。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TrajectoryEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryEvaluator.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObservabilityProperties.Evaluation evalConfig;
    private final EvaluationCore evaluationCore;

    public TrajectoryEvaluator(JdbcTemplate jdbcTemplate,
                               ObservabilityProperties properties,
                               EvaluationCore evaluationCore) {
        this.jdbcTemplate = jdbcTemplate;
        this.evalConfig = properties.getEvaluation();
        this.evaluationCore = evaluationCore;
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
     * 委托 EvaluationCore 执行五维评估。
     */
    private EvaluationResult evaluate(TraceRecord trace) {
        var config = buildConfigFromProperties();
        return evaluationCore.evaluate(trace.steps(), config, trace.traceId());
    }

    /**
     * 从 ObservabilityProperties 构建 EvaluationConfig。
     */
    private EvaluationConfig buildConfigFromProperties() {
        return new EvaluationConfig(
                evalConfig.getToolSelectionWeight(),
                evalConfig.getParameterValidityWeight(),
                evalConfig.getStepEfficiencyWeight(),
                evalConfig.getPolicyComplianceWeight(),
                evalConfig.getTokenEfficiencyWeight(),
                0,  // observability 模块不设期望步骤数
                0,  // observability 模块不设期望 Token 预算
                List.of()  // observability 模块不校验工具调用序列
        );
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
}

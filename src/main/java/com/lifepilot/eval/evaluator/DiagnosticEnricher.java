package com.lifepilot.eval.evaluator;

import com.lifepilot.eval.judge.JudgeResult;
import com.lifepilot.eval.model.DiagnosticReport;
import com.lifepilot.eval.model.DiagnosticReport.DimensionDiagnostic;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.observability.evaluation.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 诊断报告生成器 — 为低分维度生成可操作的诊断和改进建议。
 *
 * <p>将五维评估的数值结果转化为人类可读的诊断信息，
 * 合并 LLM Judge 子维度诊断，输出 {@link DiagnosticReport}。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
public class DiagnosticEnricher {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticEnricher.class);

    /** 低分阈值，低于此值的维度会生成诊断。 */
    private static final double LOW_SCORE_THRESHOLD = 0.6;

    private static final Map<String, String> DIMENSION_LABELS = Map.of(
            "toolSelection", "工具选择正确性",
            "parameterValidity", "参数合法性",
            "stepEfficiency", "步骤效率",
            "policyCompliance", "策略合规性",
            "tokenEfficiency", "Token 效率"
    );

    /**
     * 生成诊断报告。
     *
     * @param coreResult  五维评估结果
     * @param judgeResult LLM Judge 结果（可选）
     * @param scenario    场景定义
     * @return 诊断报告
     */
    public DiagnosticReport enrich(EvaluationResult coreResult,
                                    @Nullable JudgeResult judgeResult,
                                    BenchmarkScenario scenario) {
        var diagnostics = new ArrayList<DimensionDiagnostic>();
        var suggestions = new ArrayList<String>();

        // 五维评估诊断
        diagnoseToolSelection(coreResult.toolSelectionScore(), scenario, diagnostics, suggestions);
        diagnoseParameterValidity(coreResult.parameterValidityScore(), diagnostics, suggestions);
        diagnoseStepEfficiency(coreResult.stepEfficiencyScore(), coreResult.actualSteps(),
                scenario.expectedStepCount(), diagnostics, suggestions);
        diagnosePolicyCompliance(coreResult.policyComplianceScore(), diagnostics, suggestions);
        diagnoseTokenEfficiency(coreResult.tokenEfficiencyScore(), coreResult.actualTokens(),
                scenario.expectedTokenBudget(), diagnostics, suggestions);

        // 合并 LLM Judge 子维度诊断
        if (judgeResult != null && !judgeResult.dimensionScores().isEmpty()) {
            for (var entry : judgeResult.dimensionScores().entrySet()) {
                if (entry.getValue() < LOW_SCORE_THRESHOLD) {
                    diagnostics.add(new DimensionDiagnostic(
                            "judge-" + entry.getKey(),
                            "语义评判-" + translateJudgeDimension(entry.getKey()),
                            entry.getValue(),
                            "LLM Judge 在「%s」维度评分偏低".formatted(translateJudgeDimension(entry.getKey())),
                            List.of()
                    ));
                }
            }
            if (judgeResult.suggestions() != null) {
                suggestions.addAll(judgeResult.suggestions());
            }
        }

        // 总体评估
        String overall = buildOverallAssessment(coreResult.overallScore(), diagnostics.size());

        log.debug("诊断报告生成完成: scenarioId={}, 低分维度数={}, 建议数={}",
                scenario.id(), diagnostics.size(), suggestions.size());

        return new DiagnosticReport(diagnostics, suggestions, overall);
    }

    private void diagnoseToolSelection(double score, BenchmarkScenario scenario,
                                        List<DimensionDiagnostic> diagnostics,
                                        List<String> suggestions) {
        if (score >= LOW_SCORE_THRESHOLD) return;

        var expected = scenario.expectedToolCalls();
        String diagnosis = expected.isEmpty()
                ? "Agent 调用了不必要的工具"
                : "Agent 未按期望调用工具。期望工具序列: %s".formatted(String.join(" → ", expected));

        var fixes = new ArrayList<String>();
        fixes.add("检查工具描述是否清晰，确保 Agent 能正确理解工具用途");
        if (!expected.isEmpty()) {
            fixes.add("确认期望工具 %s 已注册且可用".formatted(expected));
        }

        diagnostics.add(new DimensionDiagnostic("toolSelection",
                DIMENSION_LABELS.get("toolSelection"), score, diagnosis, fixes));
        suggestions.add("优化工具描述或调整 Prompt 引导 Agent 选择正确工具");
    }

    private void diagnoseParameterValidity(double score,
                                            List<DimensionDiagnostic> diagnostics,
                                            List<String> suggestions) {
        if (score >= LOW_SCORE_THRESHOLD) return;

        diagnostics.add(new DimensionDiagnostic("parameterValidity",
                DIMENSION_LABELS.get("parameterValidity"), score,
                "工具调用参数存在错误，部分调用返回失败",
                List.of("检查工具 inputSchema 定义是否完整", "在 Prompt 中补充参数格式示例")));
        suggestions.add("完善工具参数 Schema 定义，增加参数校验提示");
    }

    private void diagnoseStepEfficiency(double score, int actualSteps, int expectedSteps,
                                         List<DimensionDiagnostic> diagnostics,
                                         List<String> suggestions) {
        if (score >= LOW_SCORE_THRESHOLD) return;

        String diagnosis = expectedSteps > 0
                ? "Agent 使用了 %d 步完成任务，期望 %d 步，存在 %d 个冗余步骤".formatted(
                        actualSteps, expectedSteps, Math.max(0, actualSteps - expectedSteps))
                : "Agent 执行步骤数偏多: %d 步".formatted(actualSteps);

        diagnostics.add(new DimensionDiagnostic("stepEfficiency",
                DIMENSION_LABELS.get("stepEfficiency"), score, diagnosis,
                List.of("检查是否存在重复的工具调用", "优化 Prompt 减少不必要的推理步骤")));
        suggestions.add("减少冗余步骤，优化 Agent 决策路径");
    }

    private void diagnosePolicyCompliance(double score,
                                           List<DimensionDiagnostic> diagnostics,
                                           List<String> suggestions) {
        if (score >= LOW_SCORE_THRESHOLD) return;

        diagnostics.add(new DimensionDiagnostic("policyCompliance",
                DIMENSION_LABELS.get("policyCompliance"), score,
                "Agent 触发了安全护栏拦截，存在策略违规行为",
                List.of("检查 Agent 输出是否包含敏感内容", "调整 Prompt 中的安全约束指令")));
        suggestions.add("加强 Prompt 中的安全边界约束");
    }

    private void diagnoseTokenEfficiency(double score, int actualTokens, int expectedBudget,
                                          List<DimensionDiagnostic> diagnostics,
                                          List<String> suggestions) {
        if (score >= LOW_SCORE_THRESHOLD) return;

        String diagnosis = expectedBudget > 0
                ? "Token 消耗 %d 超出预算 %d（超出 %.0f%%）".formatted(
                        actualTokens, expectedBudget,
                        (actualTokens - expectedBudget) * 100.0 / expectedBudget)
                : "Token 消耗偏高: %d".formatted(actualTokens);

        diagnostics.add(new DimensionDiagnostic("tokenEfficiency",
                DIMENSION_LABELS.get("tokenEfficiency"), score, diagnosis,
                List.of("精简 System Prompt 减少输入 Token", "考虑使用更小的模型处理简单任务")));
        suggestions.add("优化 Prompt 长度或调整模型选择以降低 Token 消耗");
    }

    private String translateJudgeDimension(String key) {
        return switch (key) {
            case "accuracy" -> "准确性";
            case "completeness" -> "完整性";
            case "safety" -> "安全性";
            case "style" -> "表达质量";
            default -> key;
        };
    }

    private String buildOverallAssessment(double overallScore, int issueCount) {
        if (issueCount == 0) {
            return "所有评估维度表现良好，综合评分 %.2f".formatted(overallScore);
        }
        String level = overallScore >= 0.7 ? "基本合格" : overallScore >= 0.4 ? "需要改进" : "严重不足";
        return "综合评分 %.2f（%s），共发现 %d 个低分维度需要关注".formatted(overallScore, level, issueCount);
    }
}

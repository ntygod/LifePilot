package com.lifepilot.eval.model;

import java.util.List;

/**
 * 诊断报告 — 为评估结果提供可操作的维度级诊断和改进建议。
 *
 * @param dimensionDiagnostics 各维度诊断详情
 * @param actionableSuggestions 可操作的改进建议
 * @param overallAssessment    总体评估摘要
 * @author zsg
 * @since 2026-03-22
 */
public record DiagnosticReport(
        List<DimensionDiagnostic> dimensionDiagnostics,
        List<String> actionableSuggestions,
        String overallAssessment
) {
    public DiagnosticReport {
        dimensionDiagnostics = dimensionDiagnostics != null ? List.copyOf(dimensionDiagnostics) : List.of();
        actionableSuggestions = actionableSuggestions != null ? List.copyOf(actionableSuggestions) : List.of();
        overallAssessment = overallAssessment != null ? overallAssessment : "";
    }

    /**
     * 单维度诊断详情。
     *
     * @param dimension 维度英文标识
     * @param label     维度中文标签
     * @param score     该维度评分
     * @param diagnosis 具体诊断描述
     * @param fixes     修复建议列表
     */
    public record DimensionDiagnostic(
            String dimension,
            String label,
            double score,
            String diagnosis,
            List<String> fixes
    ) {
        public DimensionDiagnostic {
            fixes = fixes != null ? List.copyOf(fixes) : List.of();
        }
    }
}

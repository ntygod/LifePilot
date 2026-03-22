package com.lifepilot.eval.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评估结果 record。
 *
 * @param evalId                评估 ID（UUID）
 * @param traceId               轨迹 ID
 * @param scenarioId            场景 ID
 * @param dimensionScores       各维度评分
 * @param overallScore          综合评分（加权平均）
 * @param violations            所有违规项
 * @param suggestions           所有改进建议
 * @param llmJudgeScore         LLM 评判评分（可选）
 * @param llmJudgeJustification LLM 评判理由（可选）
 * @param llmJudgeTokensUsed    LLM 评判消耗 Token
 * @param evaluatedAt           评估时间
 * @param gitCommitHash         Git commit hash
 * @param gitBranch             Git 分支名
 * @param evalRunId             评估运行 ID
 * @param diagnosticJson        诊断报告 JSON（可选）
 * @param runMetadataJson       运行元数据 JSON（可选）
 * @author zsg
 * @since 2026-08-01
 */
@Builder(toBuilder = true)
public record EvalResult(
        String evalId,
        String traceId,
        String scenarioId,
        Map<String, Double> dimensionScores,
        double overallScore,
        List<String> violations,
        List<String> suggestions,
        @Nullable Double llmJudgeScore,
        @Nullable String llmJudgeJustification,
        int llmJudgeTokensUsed,
        Instant evaluatedAt,
        @Nullable String gitCommitHash,
        @Nullable String gitBranch,
        String evalRunId,
        @Nullable String diagnosticJson,
        @Nullable String runMetadataJson
) {
    public EvalResult {
        dimensionScores = dimensionScores != null ? Map.copyOf(dimensionScores) : Map.of();
        violations = violations != null ? List.copyOf(violations) : List.of();
        suggestions = suggestions != null ? List.copyOf(suggestions) : List.of();
    }

    /**
     * 是否通过评估。
     *
     * @param threshold 通过阈值
     * @return 综合评分 >= 阈值时返回 true
     */
    public boolean passed(double threshold) {
        return overallScore >= threshold;
    }
}

package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * 轨迹质量评估器 — 评估 ReAct 轨迹是否适合提炼经验。
 *
 * <p>纯函数组件，不依赖外部服务。评估维度包括：
 * goal 清晰度、轨迹完整性、工具调用有效率、挂起状态、终止原因。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class TrajectoryQualityAssessor {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryQualityAssessor.class);

    private final MemoryProperties.Experience config;

    public TrajectoryQualityAssessor(MemoryProperties properties) {
        this.config = properties.getExperience();
    }

    /**
     * 评估轨迹质量。
     *
     * @param state Agent 最终状态
     * @return 质量报告
     */
    public TrajectoryQualityReport assess(ReactAgentState state) {
        return assess(state, null);
    }

    /**
     * 评估轨迹质量（含 Eval 补充维度）。
     *
     * @param state      Agent 最终状态
     * @param evalResult Eval 评估结果（可选）
     * @return 质量报告
     */
    public TrajectoryQualityReport assess(ReactAgentState state, @Nullable EvalResult evalResult) {
        // 1. goal 清晰度：非空且长度 ≥ 2
        boolean goalClarity = state.goal() != null && state.goal().length() >= 2;

        // 2. 轨迹完整性：含 Answer 步骤或 finalOutput 非空
        boolean trajectoryCompleteness = state.finalOutput() != null
                || state.steps().stream().anyMatch(s -> s instanceof ReactStep.Answer);

        // 3. 计算 toolSuccessRatio
        long totalObservations = state.steps().stream()
                .filter(s -> s instanceof ReactStep.Observation)
                .count();
        long successObservations = state.steps().stream()
                .filter(s -> s instanceof ReactStep.Observation obs && obs.success())
                .count();
        float toolSuccessRatio = totalObservations > 0
                ? (float) successObservations / totalObservations
                : 0f;

        // 4. 任务是否成功（terminationReason 为 null 表示正常完成）
        boolean taskSuccess = state.terminationReason() == null;

        int totalSteps = state.stepCount();

        // 5. Eval 补充维度
        Double evalOverallScore = evalResult != null ? evalResult.overallScore() : null;
        var evalDimensionScores = evalResult != null ? evalResult.dimensionScores() : null;

        // 6. 综合门控判定
        float effectiveMinRatio = config.getMinToolSuccessRatio();
        if (evalResult != null && evalResult.overallScore() >= 0.7) {
            // Eval 高分时放宽 toolSuccessRatio 门控
            effectiveMinRatio *= config.getEvalQualityRelaxFactor();
        }

        boolean qualityPassed = goalClarity
                && trajectoryCompleteness
                && !state.suspended()
                && (totalObservations == 0 || toolSuccessRatio >= effectiveMinRatio);

        if (!qualityPassed) {
            log.debug("轨迹质量未通过: goalClarity={}, completeness={}, suspended={}, "
                            + "toolSuccessRatio={}, minRatio={}, sessionId={}",
                    goalClarity, trajectoryCompleteness, state.suspended(),
                    toolSuccessRatio, effectiveMinRatio, state.sessionId());
        }

        return new TrajectoryQualityReport(
                goalClarity,
                trajectoryCompleteness,
                toolSuccessRatio,
                taskSuccess,
                totalSteps,
                qualityPassed,
                evalOverallScore,
                evalDimensionScores
        );
    }
}

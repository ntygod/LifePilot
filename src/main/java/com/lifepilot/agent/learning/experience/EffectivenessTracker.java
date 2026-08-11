package com.lifepilot.agent.learning.experience;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.WeightSource;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 效果追踪器 — 追踪注入经验的有效性并动态调整 importanceScore。
 *
 * <p>在 asyncPostProcess 的 Virtual Thread 中调用，零热路径影响。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class EffectivenessTracker {

    private static final Logger log = LoggerFactory.getLogger(EffectivenessTracker.class);

    private final SemanticMemory semanticMemory;
    private final InjectionRecordRepository injectionRecordRepository;
    private final AgentLearningProperties.Experience.Effectiveness config;

    public EffectivenessTracker(SemanticMemory semanticMemory,
                                 InjectionRecordRepository injectionRecordRepository,
                                 AgentLearningProperties AgentLearningProperties) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        this.injectionRecordRepository = Objects.requireNonNull(
                injectionRecordRepository, "injectionRecordRepository 不能为空");
        this.config = Objects.requireNonNull(AgentLearningProperties, "AgentLearningProperties 不能为空")
                .getExperience().getEffectiveness();
    }

    /**
     * 记录经验注入事件。在 ContextAssembler 注入经验时调用。
     *
     * @param traceId   执行 traceId
     * @param entityIds 注入的经验实体 ID 列表
     */
    public void recordInjection(String traceId, List<String> entityIds) {
        requireCanonicalId(traceId, "traceId");
        validateEntityIds(entityIds, traceId);
        injectionRecordRepository.saveWithType(
                traceId,
                traceId,
                entityIds,
                "EXPERIENCE");
    }

    /**
     * 评估本次任务中注入经验的有效性。在 asyncPostProcess 的 Virtual Thread 中调用。
     *
     * @param state   Agent 最终状态
     * @param traceId 本次执行的 traceId
     */
    public void evaluate(ReactAgentState state, String traceId) {
        Objects.requireNonNull(state, "state 不能为空");
        requireCanonicalId(traceId, "traceId");
        validateConfig();
        // 查询本次注入的经验 ID
        var injectedIds = Objects.requireNonNull(
                injectionRecordRepository.findEntityIdsBySourceTraceIdAndType(traceId, "EXPERIENCE"),
                "注入记录查询结果不能为空");
        if (injectedIds.isEmpty()) {
            log.debug("效果评估: 无注入记录，跳过, traceId={}", traceId);
            return;
        }
        validateEntityIds(injectedIds, traceId);

        // 计算 toolSuccessRatio
        float toolSuccessRatio = calcToolSuccessRatio(state);
        // 判定有效性：terminationReason == null 且 toolSuccessRatio >= threshold
        boolean effective = state.terminationReason() == null
                && toolSuccessRatio >= config.getSuccessRatioThreshold();

        log.debug("效果评估: traceId={}, effective={}, toolSuccessRatio={}, injectedCount={}",
                traceId, effective, toolSuccessRatio, injectedIds.size());

        for (String entityId : injectedIds) {
            adjustScore(entityId, effective);
        }
    }

    /**
     * 计算工具调用成功率。
     */
    private float calcToolSuccessRatio(ReactAgentState state) {
        var steps = Objects.requireNonNull(state.steps(), "Agent 步骤不能为空");
        if (steps.isEmpty()) return 0.0f;

        long totalObs = 0;
        long successObs = 0;
        for (var step : steps) {
            if (step instanceof ReactStep.Observation obs) {
                totalObs++;
                if (obs.success()) successObs++;
            }
        }
        return totalObs == 0 ? 0.0f : (float) successObs / totalObs;
    }

    /**
     * 调整单个经验的 importanceScore。
     */
    private void adjustScore(String entityId, boolean effective) {
        var optEntity = semanticMemory.findById(entityId);
        if (optEntity == null) {
            throw new IllegalStateException("效果评估: 注入经验查询结果不能为空, entityId=" + entityId);
        }
        var entity = optEntity
                .orElseThrow(() -> new IllegalStateException("效果评估: 注入经验实体不存在, entityId=" + entityId));
        if (entity.type() != EntityType.EXPERIENCE) {
            throw new IllegalStateException("效果评估: 注入实体不是 EXPERIENCE, entityId=" + entityId);
        }
        float currentScore = entity.importanceScore();
        if (!Float.isFinite(currentScore) || currentScore < 0.0f || currentScore > 1.0f) {
            throw new IllegalStateException("效果评估: importanceScore 必须在 [0,1] 范围内, entityId="
                    + entityId + ", score=" + currentScore);
        }
        float newScore;

        if (effective) {
            newScore = Math.min(currentScore + config.getPositiveBoost(), 1.0f);
        } else {
            newScore = Math.max(currentScore - config.getNegativeDecay(), 0.0f);
        }

        if (newScore < config.getEvictionThreshold()) {
            // 淘汰低分经验
            SqliteBusyRetry.run(() -> semanticMemory.archive(entity, ChangeSource.NEGATIVE_FEEDBACK));
            log.info("效果评估: 经验淘汰, entityId={}, score={}", entityId, newScore);
        } else {
            SqliteBusyRetry.run(() -> semanticMemory.updateImportanceScore(
                    entityId, newScore, WeightSource.EFFECTIVENESS));
            log.debug("效果评估: 分数调整, entityId={}, oldScore={}, newScore={}, effective={}",
                    entityId, currentScore, newScore, effective);
        }
    }

    private void validateConfig() {
        requireRatio(config.getSuccessRatioThreshold(), "效果评估成功率阈值");
        requireRatio(config.getPositiveBoost(), "效果评估正向提升步长");
        requireRatio(config.getNegativeDecay(), "效果评估负向衰减步长");
        requireRatio(config.getEvictionThreshold(), "效果评估淘汰阈值");
    }

    private static void validateEntityIds(List<String> entityIds, String traceId) {
        Objects.requireNonNull(entityIds, "entityIds 不能为空");
        for (String entityId : entityIds) {
            requireCanonicalId(entityId, "注入经验实体 ID");
        }
    }

    private static void requireCanonicalId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + "不能包含首尾空白: " + value);
        }
    }

    private static void requireRatio(float value, String label) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(label + "必须在 [0,1] 范围内: " + value);
        }
    }
}

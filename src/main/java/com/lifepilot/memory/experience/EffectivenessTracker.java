package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
    private final MemoryProperties.Experience.Effectiveness config;

    public EffectivenessTracker(SemanticMemory semanticMemory,
                                 InjectionRecordRepository injectionRecordRepository,
                                 MemoryProperties memoryProperties) {
        this.semanticMemory = semanticMemory;
        this.injectionRecordRepository = injectionRecordRepository;
        this.config = memoryProperties.getExperience().getEffectiveness();
    }

    /**
     * 记录经验注入事件。在 ContextAssembler 注入经验时调用。
     *
     * @param traceId   执行 traceId
     * @param entityIds 注入的经验实体 ID 列表
     */
    public void recordInjection(String traceId, List<String> entityIds) {
        try {
            injectionRecordRepository.saveWithType(traceId, traceId, entityIds, "EXPERIENCE");
        } catch (Exception e) {
            log.warn("经验注入记录失败: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /**
     * 评估本次任务中注入经验的有效性。在 asyncPostProcess 的 Virtual Thread 中调用。
     *
     * @param state   Agent 最终状态
     * @param traceId 本次执行的 traceId
     */
    public void evaluate(ReactAgentState state, String traceId) {
        try {
            // 查询本次注入的经验 ID
            var injectedIds = injectionRecordRepository.findEntityIdsBySourceTraceIdAndType(traceId, "EXPERIENCE");
            if (injectedIds.isEmpty()) {
                log.debug("效果评估: 无注入记录，跳过, traceId={}", traceId);
                return;
            }

            // 计算 toolSuccessRatio
            float toolSuccessRatio = calcToolSuccessRatio(state);
            // 判定有效性：terminationReason == null 且 toolSuccessRatio >= threshold
            boolean effective = state.terminationReason() == null
                    && toolSuccessRatio >= config.getSuccessRatioThreshold();

            log.debug("效果评估: traceId={}, effective={}, toolSuccessRatio={}, injectedCount={}",
                    traceId, effective, toolSuccessRatio, injectedIds.size());

            for (String entityId : injectedIds) {
                try {
                    adjustScore(entityId, effective);
                } catch (Exception e) {
                    log.warn("效果评估: 调整分数失败, entityId={}, error={}", entityId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("效果评估失败: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /**
     * 计算工具调用成功率。
     */
    private float calcToolSuccessRatio(ReactAgentState state) {
        var steps = state.steps();
        if (steps == null || steps.isEmpty()) return 0.0f;

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
        semanticMemory.findById(entityId).ifPresent(entity -> {
            float currentScore = entity.importanceScore();
            float newScore;

            if (effective) {
                newScore = Math.min(currentScore + config.getPositiveBoost(), 1.0f);
            } else {
                newScore = Math.max(currentScore - config.getNegativeDecay(), 0.0f);
            }

            if (newScore < config.getEvictionThreshold()) {
                // 淘汰低分经验
                semanticMemory.archive(entity);
                log.info("效果评估: 经验淘汰, entityId={}, score={}", entityId, newScore);
            } else {
                semanticMemory.updateImportanceScore(entityId, newScore);
                log.debug("效果评估: 分数调整, entityId={}, oldScore={}, newScore={}, effective={}",
                        entityId, currentScore, newScore, effective);
            }
        });
    }
}

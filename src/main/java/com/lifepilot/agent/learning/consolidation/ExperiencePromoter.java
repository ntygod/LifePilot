package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import com.lifepilot.memory.store.procedural.TemplateStep;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 经验提升器 — 巩固阶段 6：将高频经验提升为 L4 ProcedureTemplate。
 *
 * <p>从 {@link ConsolidationPipeline} 私有方法抽取为独立可调用单元，
 * 供 {@link ConsolidationScheduler} 在每日 cron 阶段独立触发。</p>
 *
 * <p>提升条件：L3 {@code EXPERIENCE} 实体的 {@code importanceScore} 和 {@code accessCount}
 * 同时达到配置阈值。提升后的模板 {@code sourceEntityId} 指向源 L3 EXPERIENCE，
 * 源实体保持 ACTIVE，后续失活时由 L4SyncListener 级联模板失活。</p>
 *
 * @author zsg
 * @since 2026-06-05
 */
public class ExperiencePromoter {

    private static final Logger log = LoggerFactory.getLogger(ExperiencePromoter.class);

    private final SemanticMemory semanticMemory;
    private final ProceduralMemory proceduralMemory;
    private final AgentLearningProperties properties;

    public ExperiencePromoter(SemanticMemory semanticMemory,
                              ProceduralMemory proceduralMemory,
                              AgentLearningProperties properties) {
        this.semanticMemory = semanticMemory;
        this.proceduralMemory = proceduralMemory;
        this.properties = properties;
    }

    /**
     * 将高频经验（importanceScore 和 accessCount 达到配置阈值）提升为 L4 ProcedureTemplate。
     *
     * @return 提升的经验数量
     */
    public int promote() {
        var consolidationConfig = properties.getConsolidation();
        float minImportance = consolidationConfig.getExperiencePromoteMinImportance();
        int minAccessCount = consolidationConfig.getExperiencePromoteMinAccessCount();

        var experiences = semanticMemory.findCurrentByType(EntityType.EXPERIENCE);
        int promoted = 0;

        for (var exp : experiences) {
            if (exp.importanceScore() >= minImportance && exp.accessCount() >= minAccessCount) {
                // 检查是否已存在同名模板（避免重复提升）
                var existing = proceduralMemory.findById(exp.id());
                if (existing.isPresent()) continue;

                var now = Instant.now();
                // 经验提升为 L4 模板 — sourceEntityId 指向源 L3 EXPERIENCE 实体 id。
                // 源实体保持 ACTIVE：后续真正失活时再由 L4SyncListener 级联模板失活。
                //
                // 可靠性初始化：useCount 由源经验 accessCount 驱动，忠实反映底层经验
                // 已被使用的次数。提升前提已要求 accessCount >= minAccessCount（默认 3），
                // 故 useCount >= minUseCount（默认 2），配合 successRate=1.0 使提升模板
                // 立即满足 IntentMatcher.isReliable 而可被匹配，修复 useCount=0 永不可靠的缺陷。
                var template = new ProcedureTemplate(
                        exp.id(),
                        exp.name(),
                        exp.description(),
                        exp.name(),
                        List.of(new TemplateStep(1, "experience", "apply",
                                Map.of("scenario", exp.name()), exp.description(), false)),
                        Map.of(),
                        1.0f,
                        exp.accessCount(),
                        null,
                        List.of(exp.sourceConversationId()),
                        now,
                        now,
                        exp.id(),
                        null
                );
                SqliteBusyRetry.run(() -> proceduralMemory.save(template));
                promoted++;
                log.debug("经验提升: entityId={}, name={}", exp.id(), exp.name());
            }
        }
        if (promoted > 0) {
            log.info("经验提升: 完成, promoted={}", promoted);
        }
        return promoted;
    }
}

package com.lifepilot.agent.learning.consolidation.association;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REM 联想候选落库应用器 —— 将 {@link AssociationCandidateStore} 中的文件候选
 * 应用到 {@code memory_relations} 主库，补齐"睡眠式联想 → 知识图谱边"的最后一环。
 *
 * <p>应用规则：
 * <ol>
 *   <li>置信度过滤（{@code rem.apply-min-confidence}，独立于生成阈值，默认更高）</li>
 *   <li>端点存活校验（source/target 必须为当前有效实体）</li>
 *   <li>幂等（同 source:target:type 的 ACTIVE 边已存在则跳过）</li>
 * </ol>
 * </p>
 *
 * @author zsg
 * @since 2026-06-06
 */
public class AssociationCandidateApplier {

    private static final Logger log = LoggerFactory.getLogger(AssociationCandidateApplier.class);

    private final AgentLearningProperties properties;
    private final AssociationCandidateStore store;
    private final SemanticMemory semanticMemory;

    public AssociationCandidateApplier(AgentLearningProperties properties,
                                       AssociationCandidateStore store,
                                       SemanticMemory semanticMemory) {
        this.properties = Objects.requireNonNull(properties);
        this.store = Objects.requireNonNull(store);
        this.semanticMemory = Objects.requireNonNull(semanticMemory);
    }

    /** 应用指定日期的候选到主库；返回处理结果统计。 */
    public ApplyResult apply(LocalDate date) {
        if (date == null) {
            return ApplyResult.empty();
        }
        List<AssociationCandidate> candidates = store.load(date);
        if (candidates.isEmpty()) {
            return ApplyResult.empty();
        }
        float minConfidence = properties.getRem().getApplyMinConfidence();
        int applied = 0;
        int skippedLowConfidence = 0;
        int skippedMissingEntity = 0;
        int skippedDuplicate = 0;

        for (var c : candidates) {
            if (c.confidence() < minConfidence) {
                skippedLowConfidence++;
                continue;
            }
            if (c.sourceEntityId().equals(c.targetEntityId())
                    || !semanticMemory.existsCurrentById(c.sourceEntityId())
                    || !semanticMemory.existsCurrentById(c.targetEntityId())) {
                skippedMissingEntity++;
                continue;
            }
            String relationType = c.relationType().name();
            if (semanticMemory.relationExists(c.sourceEntityId(), c.targetEntityId(), relationType)) {
                skippedDuplicate++;
                continue;
            }
            var now = Instant.now();
            float relTrust = MemoryQualityPolicy.trustScoreFor(
                    MemoryEvidenceKind.DERIVED, c.confidence());
            var relation = new TemporalRelation(
                    UUID.randomUUID().toString(),
                    c.sourceEntityId(),
                    c.targetEntityId(),
                    relationType,
                    c.confidence(),
                    null,
                    now,
                    null,
                    "rem:" + c.seedEntityId(),
                    now,
                    MemoryEvidenceKind.DERIVED,
                    MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.DERIVED, relTrust),
                    relTrust);
            SqliteBusyRetry.run(() -> semanticMemory.addRelation(
                    relation,
                    MemoryWriteContext.consolidation("rem:" + c.seedEntityId())));
            applied++;
        }
        var result = new ApplyResult(candidates.size(), applied,
                skippedLowConfidence, skippedMissingEntity, skippedDuplicate);
        log.info("REM 落库应用: date={}, {}", date, result);
        return result;
    }

    /** 应用结果统计。 */
    public record ApplyResult(int input, int applied, int skippedLowConfidence,
                              int skippedMissingEntity, int skippedDuplicate) {
        static ApplyResult empty() {
            return new ApplyResult(0, 0, 0, 0, 0);
        }
    }
}

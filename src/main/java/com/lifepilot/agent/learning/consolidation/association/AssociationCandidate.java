package com.lifepilot.agent.learning.consolidation.association;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * REM 式联想候选 — 表示待审计的潜在实体关系。
 *
 * <p>由 {@link AssociationCandidateGenerator} 产出，由 {@link AssociationConsolidator}
 * 过滤去重后持久化到 {@link AssociationCandidateStore}。</p>
 *
 * @param sourceEntityId 源实体 id
 * @param targetEntityId 目标实体 id
 * @param relationType   关联类型
 * @param confidence     置信度 [0, 1]
 * @param evidence       LLM 推理证据摘要
 * @param seedEntityId   触发生成本候选的 seed 实体 id
 * @param generatedAt    生成时间
 * @author zsg
 * @since 2026-05-09
 */
public record AssociationCandidate(
        String sourceEntityId,
        String targetEntityId,
        AssociationType relationType,
        float confidence,
        @Nullable String evidence,
        String seedEntityId,
        Instant generatedAt
) {

    @JsonCreator
    public AssociationCandidate(
            @JsonProperty("sourceEntityId") String sourceEntityId,
            @JsonProperty("targetEntityId") String targetEntityId,
            @JsonProperty("relationType") AssociationType relationType,
            @JsonProperty("confidence") float confidence,
            @JsonProperty("evidence") @Nullable String evidence,
            @JsonProperty("seedEntityId") String seedEntityId,
            @JsonProperty("generatedAt") Instant generatedAt) {
        if (sourceEntityId == null || sourceEntityId.isBlank())
            throw new IllegalArgumentException("sourceEntityId 不能为空");
        if (!sourceEntityId.equals(sourceEntityId.trim()))
            throw new IllegalArgumentException("sourceEntityId 不能包含首尾空白: " + sourceEntityId);
        if (targetEntityId == null || targetEntityId.isBlank())
            throw new IllegalArgumentException("targetEntityId 不能为空");
        if (!targetEntityId.equals(targetEntityId.trim()))
            throw new IllegalArgumentException("targetEntityId 不能包含首尾空白: " + targetEntityId);
        if (relationType == null)
            throw new IllegalArgumentException("relationType 不能为空");
        if (seedEntityId == null || seedEntityId.isBlank())
            throw new IllegalArgumentException("seedEntityId 不能为空");
        if (!seedEntityId.equals(seedEntityId.trim()))
            throw new IllegalArgumentException("seedEntityId 不能包含首尾空白: " + seedEntityId);
        if (!(confidence >= 0f && confidence <= 1f))
            throw new IllegalArgumentException("confidence 必须在 [0,1] 范围内");
        if (generatedAt == null)
            throw new IllegalArgumentException("generatedAt 不能为空");
        this.sourceEntityId = sourceEntityId;
        this.targetEntityId = targetEntityId;
        this.relationType = relationType;
        this.confidence = confidence;
        this.evidence = evidence;
        this.seedEntityId = seedEntityId;
        this.generatedAt = generatedAt;
    }

    /** 去重 key：source + target + type。 */
    public String dedupKey() {
        return sourceEntityId + ":" + targetEntityId + ":" + relationType.name();
    }
}

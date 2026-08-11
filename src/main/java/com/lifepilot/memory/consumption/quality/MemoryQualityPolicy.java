package com.lifepilot.memory.consumption.quality;

import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.store.entity.TemporalEntity;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 记忆质量策略。
 *
 * <p>集中维护来源类型、可信等级与可信分的推导，避免各入口各自硬编码。</p>
 *
 * @author zsg
 * @since 2026-05-05
 */
public final class MemoryQualityPolicy {

    private MemoryQualityPolicy() {
    }

    public static TemporalEntity requireWritableQuality(TemporalEntity entity) {
        Objects.requireNonNull(entity, "待写入实体不能为空");
        if (entity.evidenceKind() == MemoryEvidenceKind.UNKNOWN) {
            throw new IllegalArgumentException("语义记忆写入必须指定有效证据类型");
        }
        if (entity.trustLevel() == MemoryTrustLevel.UNVERIFIED) {
            throw new IllegalArgumentException("语义记忆写入必须指定可消费的可信等级");
        }
        if (!(entity.trustScore() > 0.0f && entity.trustScore() <= 1.0f)) {
            throw new IllegalArgumentException("语义记忆写入可信分必须在 (0,1] 范围内: " + entity.trustScore());
        }
        if (entity.evidenceCount() <= 0) {
            throw new IllegalArgumentException("语义记忆写入证据数量必须大于 0: " + entity.evidenceCount());
        }
        return entity;
    }

    public static MemoryEvidenceKind evidenceKindFromDecision(AudnDecision decision) {
        return parseEvidenceKind(decision.evidenceKindRaw());
    }

    public static MemoryTrustLevel trustLevelFor(MemoryEvidenceKind evidenceKind, float trustScore) {
        return inferTrustLevel(evidenceKind, trustScore);
    }

    public static float trustScoreFor(MemoryEvidenceKind evidenceKind, float extractionConfidence) {
        if (evidenceKind == MemoryEvidenceKind.UNKNOWN) {
            return 0.0f;
        }
        return Math.max(baseTrustScore(evidenceKind), clamp(extractionConfidence));
    }

    public static boolean isPromptInjectable(TemporalEntity entity) {
        if (!entity.trustLevel().isPromptInjectable()) {
            return false;
        }
        return entity.trustScore() >= minimumPromptTrust(entity.trustLevel());
    }

    /**
     * 判断实体是否允许被消费侧使用。
     *
     * <p>这是上下文注入、工具搜索返回、工具级经验提示共享的硬门槛：
     * 可信质量通过、生命周期仍可召回，并且实体本身未过期。相关度、token 预算、
     * toolId 匹配等由具体消费路径继续裁剪。</p>
     */
    public static boolean isPromptConsumable(TemporalEntity entity) {
        if (entity == null || !isPromptInjectable(entity)) {
            return false;
        }
        if (entity.lifecycleState() == null || !entity.lifecycleState().isRetrievable()) {
            return false;
        }
        Instant now = Instant.now();
        if (entity.validTo() != null && !entity.validTo().isAfter(now)) {
            return false;
        }
        return entity.expiresAt() == null || entity.expiresAt().isAfter(now);
    }

    public static boolean canPromoteToProcedural(TemporalEntity entity) {
        return entity.trustLevel().canPromoteToProcedural()
                && entity.trustScore() >= 0.70f;
    }

    public static MemoryEvidenceKind parseEvidenceKind(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("证据类型不能为空");
        }
        if (!raw.equals(raw.trim())) {
            throw new IllegalArgumentException("证据类型不能包含首尾空白: " + raw);
        }
        try {
            return MemoryEvidenceKind.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知证据类型: " + raw, ex);
        }
    }

    private static MemoryTrustLevel inferTrustLevel(MemoryEvidenceKind evidenceKind, float trustScore) {
        if (trustScore <= 0.0f || evidenceKind == MemoryEvidenceKind.UNKNOWN) {
            return MemoryTrustLevel.UNVERIFIED;
        }
        return switch (evidenceKind) {
            case TOOL_VERIFIED, DOCUMENT_GROUNDED -> trustScore >= 0.80f
                    ? MemoryTrustLevel.VERIFIED : MemoryTrustLevel.INFERRED;
            case USER_EXPLICIT, USER_CONFIRMED -> trustScore >= 0.70f
                    ? MemoryTrustLevel.EXPLICIT : MemoryTrustLevel.INFERRED;
            case LLM_SUMMARIZED_EXPERIENCE, DERIVED -> MemoryTrustLevel.DERIVED;
            case CHAT_INFERRED, BEHAVIOR_INFERRED -> trustScore >= 0.45f
                    ? MemoryTrustLevel.INFERRED : MemoryTrustLevel.UNVERIFIED;
            case UNKNOWN -> MemoryTrustLevel.UNVERIFIED;
        };
    }

    private static float baseTrustScore(MemoryEvidenceKind evidenceKind) {
        return switch (evidenceKind) {
            case USER_CONFIRMED -> 0.90f;
            case TOOL_VERIFIED -> 0.88f;
            case DOCUMENT_GROUNDED -> 0.82f;
            case USER_EXPLICIT -> 0.80f;
            case LLM_SUMMARIZED_EXPERIENCE -> 0.62f;
            case DERIVED -> 0.60f;
            case CHAT_INFERRED -> 0.55f;
            case BEHAVIOR_INFERRED -> 0.50f;
            case UNKNOWN -> 0.00f;
        };
    }

    private static float minimumPromptTrust(MemoryTrustLevel trustLevel) {
        return switch (trustLevel) {
            case VERIFIED, EXPLICIT -> 0.60f;
            case DERIVED -> 0.55f;
            case INFERRED -> 0.60f;
            case UNVERIFIED -> 1.01f;
        };
    }

    private static float clamp(float value) {
        if (!(value >= 0.0f && value <= 1.0f)) {
            throw new IllegalArgumentException("质量分数必须在 [0,1] 范围内: " + value);
        }
        return value;
    }
}

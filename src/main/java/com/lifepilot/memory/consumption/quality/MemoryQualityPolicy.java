package com.lifepilot.memory.consumption.quality;

import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.TemporalEntity;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Locale;

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

    public static TemporalEntity applyDefaults(TemporalEntity entity, MemoryWriteContext context) {
        if (entity.evidenceKind() != MemoryEvidenceKind.UNKNOWN
                && entity.trustLevel() != MemoryTrustLevel.UNVERIFIED
                && entity.trustScore() > 0.0f) {
            return entity;
        }
        MemoryEvidenceKind evidenceKind = inferEvidenceKind(entity, context);
        float trustScore = Math.max(baseTrustScore(evidenceKind), clamp(entity.extractionConfidence()));
        MemoryTrustLevel trustLevel = inferTrustLevel(evidenceKind, trustScore);
        int evidenceCount = entity.evidenceCount() > 0 ? entity.evidenceCount() : initialEvidenceCount(evidenceKind);
        Instant verifiedAt = entity.lastVerifiedAt() != null
                ? entity.lastVerifiedAt()
                : initialVerifiedAt(evidenceKind, entity.updatedAt());
        return entity.withQuality(evidenceKind, trustLevel, trustScore, evidenceCount, verifiedAt);
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
            return MemoryEvidenceKind.UNKNOWN;
        }
        try {
            return MemoryEvidenceKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MemoryEvidenceKind.UNKNOWN;
        }
    }

    private static MemoryEvidenceKind inferEvidenceKind(TemporalEntity entity, MemoryWriteContext context) {
        if (entity.isDerived()) {
            return MemoryEvidenceKind.DERIVED;
        }
        if (entity.type() == EntityType.EXPERIENCE
                && context.originType() == MemoryOriginType.CONSOLIDATION) {
            return MemoryEvidenceKind.LLM_SUMMARIZED_EXPERIENCE;
        }
        return switch (context.originType()) {
            case MANUAL -> MemoryEvidenceKind.USER_CONFIRMED;
            case TOOL -> MemoryEvidenceKind.USER_CONFIRMED;
            case KNOWLEDGE_BASE_DOCUMENT -> MemoryEvidenceKind.DOCUMENT_GROUNDED;
            case CONSOLIDATION -> MemoryEvidenceKind.DERIVED;
            case CHAT -> MemoryEvidenceKind.CHAT_INFERRED;
            case UNKNOWN -> MemoryEvidenceKind.UNKNOWN;
        };
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

    private static int initialEvidenceCount(MemoryEvidenceKind evidenceKind) {
        return evidenceKind == MemoryEvidenceKind.UNKNOWN ? 0 : 1;
    }

    @Nullable
    private static Instant initialVerifiedAt(MemoryEvidenceKind evidenceKind, Instant updatedAt) {
        return switch (evidenceKind) {
            case USER_CONFIRMED, TOOL_VERIFIED, DOCUMENT_GROUNDED -> updatedAt;
            default -> null;
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
        if (Float.isNaN(value)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

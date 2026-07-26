package com.lifepilot.interaction.web.model;

import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.TemporalEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话消息中的记忆来源摘要构造器。
 *
 * <p>主对话里的记忆标签应该解释"为什么被用上"和"会怎么影响回答"，
 * 避免只暴露一条抽象的能力或记忆 ID。</p>
 *
 * @author zsg
 * @since 2026-07-06
 */
public final class MemorySourceSummarySupport {

    private static final int NAME_MAX_LENGTH = 28;
    private static final int DESCRIPTION_MAX_LENGTH = 96;

    private MemorySourceSummarySupport() {
    }

    /**
     * 将记忆实体转为前端 {@code SourceSummary} 可消费的 Map。
     */
    public static Map<String, Object> toMemorySource(TemporalEntity entity) {
        var source = new LinkedHashMap<String, Object>();
        source.put("type", "memory");
        source.put("id", entity.id());
        source.put("name", entity.name());

        var extra = new LinkedHashMap<String, Object>();
        extra.put("sourceKind", "INJECTED");
        extra.put("sourceKindLabel", "本轮实际参考");
        extra.put("usageReason", buildUsageReason(entity));
        extra.put("usageImpact", buildUsageImpact(entity));
        extra.put("entityType", entity.type().name());
        extra.put("entityTypeLabel", entity.type().label());
        if (entity.description() != null && !entity.description().isBlank()) {
            extra.put("description", entity.description());
        }
        if (entity.sourceConversationId() != null && !entity.sourceConversationId().isBlank()) {
            extra.put("sourceConversationId", entity.sourceConversationId());
        }
        extra.put("extractionConfidence", entity.extractionConfidence());
        extra.put("importanceScore", entity.importanceScore());
        extra.put("lifecycleState", entity.lifecycleState().name());
        extra.put("temporality", entity.temporality().name());
        if (entity.expiresAt() != null) {
            extra.put("expiresAt", entity.expiresAt().toString());
        }
        extra.put("evidenceKind", entity.evidenceKind().name());
        extra.put("trustLevel", entity.trustLevel().name());
        extra.put("trustScore", entity.trustScore());
        extra.put("evidenceCount", entity.evidenceCount());
        extra.put("createdAt", entity.createdAt().toString());
        extra.put("updatedAt", entity.updatedAt().toString());
        source.put("extra", Collections.unmodifiableMap(extra));
        return Collections.unmodifiableMap(source);
    }

    private static String buildUsageReason(TemporalEntity entity) {
        String name = truncateText(entity.name(), NAME_MAX_LENGTH);
        String typeLabel = entity.type().label();
        String description = normalizeText(entity.description());
        if (description != null) {
            return "这条回答参考了「%s」这条%s：%s"
                    .formatted(name, typeLabel, sentenceText(truncateText(description, DESCRIPTION_MAX_LENGTH)));
        }
        return switch (entity.type()) {
            case PREFERENCE -> "这条回答参考了你的偏好「%s」，用来贴合表达、取舍和交互建议。".formatted(name);
            case EXPERIENCE -> "这条回答参考了经验「%s」，用来复用之前有效的做事方式。".formatted(name);
            case PROJECT -> "这条回答参考了项目背景「%s」，用来延续当前项目上下文。".formatted(name);
            case GOAL -> "这条回答参考了目标「%s」，用来让建议和后续步骤保持一致。".formatted(name);
            case SKILL -> "这条回答参考了技能「%s」，用来选择更合适的执行方式。".formatted(name);
            case HABIT -> "这条回答参考了习惯「%s」，用来减少重复确认。".formatted(name);
            case PERSON -> "这条回答参考了人物信息「%s」，用来保持称呼和背景一致。".formatted(name);
            default -> "这条回答参考了「%s」这条%s，用来延续相关上下文。".formatted(name, typeLabel);
        };
    }

    private static String buildUsageImpact(TemporalEntity entity) {
        return String.join("；",
                typeImpact(entity.type()),
                temporalityImpact(entity.temporality(), entity.expiresAt() != null),
                importanceImpact(entity.importanceScore()),
                evidenceImpact(entity.evidenceCount()));
    }

    private static String typeImpact(EntityType type) {
        return switch (type) {
            case PREFERENCE -> "会影响语气、方案取舍和界面建议";
            case EXPERIENCE -> "会帮助知微复用做事步骤";
            case PROJECT -> "会帮助知微延续项目背景";
            case GOAL -> "会帮助知微对齐目标和下一步";
            case SKILL -> "会帮助知微选择合适技能";
            case HABIT -> "会帮助知微减少重复询问";
            case PERSON -> "会帮助知微识别相关人物背景";
            default -> "会帮助知微延续对这件事的理解";
        };
    }

    private static String temporalityImpact(Temporality temporality, boolean hasExpiresAt) {
        if (hasExpiresAt) {
            return "有明确有效期";
        }
        return switch (temporality) {
            case PERSISTENT -> "长期生效";
            case SHORT_TERM -> "短期参考";
            case EPHEMERAL -> "本轮优先参考";
        };
    }

    private static String importanceImpact(float importanceScore) {
        if (importanceScore >= 0.75f) {
            return "优先级较高";
        }
        if (importanceScore <= 0.35f) {
            return "低频参考";
        }
        return "按上下文需要使用";
    }

    private static String evidenceImpact(int evidenceCount) {
        if (evidenceCount <= 0) {
            return "证据待补充";
        }
        return "有 %d 条证据支撑".formatted(evidenceCount);
    }

    private static String sentenceText(String value) {
        return value.matches(".*[。！？.!?]$") ? value : value + "。";
    }

    private static String truncateText(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().replaceAll("\\s+", " ");
    }
}

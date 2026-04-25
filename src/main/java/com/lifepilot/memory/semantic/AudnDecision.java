package com.lifepilot.memory.semantic;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * AUDN 决策结果 — LLM 结构化输出的单条实体操作决策。
 *
 * <p>每条决策描述对一个实体的操作：新增、更新、删除或跳过。
 * 由 {@link RealtimeExtractor} 通过 LLM 结构化输出获取。</p>
 *
 * <p>LLM 返回的字段名可能与 Java 定义不同（如 "op" vs "operation"），
 * 使用 {@link JsonAlias} 兼容常见变体。</p>
 *
 * <p>Task 23：新增 {@code temporality} 与 {@code expiresAt} 字段承载记忆持久度
 * 与过期时间，驱动生命周期的 Cron 回收。两字段都允许为 null，由 RealtimeExtractor
 * 按规则补默认（null → PERSISTENT / 非持久时按 temporality 自动推导 expiresAt）。</p>
 *
 * @param operation  操作类型
 * @param entityName 实体名称
 * @param entityType 实体类型
 * @param description 实体描述（ADD/UPDATE 时使用）
 * @param properties 实体属性键值对（ADD/UPDATE 时使用）
 * @param extractionConfidence LLM 提取置信度 [0.0, 1.0]，可能为 null
 * @param importanceScore 信息重要性评分 [0.0, 1.0]，可能为 null
 * @param temporalityRaw LLM 输出的 temporality 字符串（EPHEMERAL/SHORT_TERM/PERSISTENT），可能为 null
 * @param expiresAtRaw LLM 输出的 ISO 8601 到期时间字符串，可能为 null
 * @author zsg
 * @since 2026-03-05
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AudnDecision(
        @JsonAlias({"op", "action", "type"}) AudnOperation operation,
        @JsonAlias({"name", "entity_name"}) String entityName,
        @JsonAlias({"entity_type", "entitytype"}) EntityType entityType,
        @Nullable @JsonAlias({"desc"}) String description,
        @Nullable @JsonAlias({"props", "attributes"}) Map<String, Object> properties,
        @Nullable @JsonAlias({"confidence", "extraction_confidence"}) Float extractionConfidence,
        @Nullable @JsonAlias({"importance", "importance_score"}) Float importanceScore,
        @Nullable @JsonAlias({"temporality"}) String temporalityRaw,
        @Nullable @JsonAlias({"expires_at", "expiresAt"}) String expiresAtRaw
) {

    /**
     * 兼容老构造点的 7 参构造器 — temporality/expiresAt 全为 null，
     * 交给 {@code RealtimeExtractor} 按规则补默认（PERSISTENT，不自动推导过期时间）。
     */
    public AudnDecision(
            AudnOperation operation,
            String entityName,
            EntityType entityType,
            @Nullable String description,
            @Nullable Map<String, Object> properties,
            @Nullable Float extractionConfidence,
            @Nullable Float importanceScore
    ) {
        this(operation, entityName, entityType, description, properties,
                extractionConfidence, importanceScore, null, null);
    }
}

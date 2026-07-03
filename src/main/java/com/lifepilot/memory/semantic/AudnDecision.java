package com.lifepilot.memory.semantic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lifepilot.agent.learning.extraction.RealtimeExtractor;
import com.lifepilot.memory.store.entity.EntityType;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * AUDN 决策结果 — LLM 结构化输出的单条实体操作决策。
 *
 * <p>每条决策描述对一个实体的操作：新增、更新、删除或跳过。
 * 由 {@link RealtimeExtractor} 通过 LLM 结构化输出获取。</p>
 *
 * <p>{@code temporality} 与 {@code expiresAt} 字段承载记忆持久度
 * 与过期时间，驱动生命周期的 Cron 回收。{@code temporality} 是 LLM 契约必填字段；
 * {@code expiresAt} 可为空，由 RealtimeExtractor 按 temporality 自动推导非持久实体过期时间。</p>
 *
 * @param operation  操作类型
 * @param entityName 实体名称
 * @param entityType 实体类型
 * @param description 实体描述（ADD/UPDATE 时使用）
 * @param properties 实体属性键值对（ADD/UPDATE 时使用）
 * @param extractionConfidence LLM 提取置信度 [0.0, 1.0]，LLM 契约要求必填
 * @param importanceScore 信息重要性评分 [0.0, 1.0]，LLM 契约要求必填
 * @param temporalityRaw LLM 输出的 temporality 字符串（EPHEMERAL/SHORT_TERM/PERSISTENT）
 * @param expiresAtRaw LLM 输出的 ISO 8601 到期时间字符串，可能为 null
 * @param evidenceKindRaw 证据类型字符串，可能为 null
 * @param evidenceExcerpt 最小必要证据片段，可能为 null
 * @author zsg
 * @since 2026-03-05
 */
public record AudnDecision(
        AudnOperation operation,
        String entityName,
        EntityType entityType,
        @Nullable String description,
        @Nullable Map<String, Object> properties,
        @Nullable Float extractionConfidence,
        @Nullable Float importanceScore,
        @Nullable @JsonProperty("temporality") String temporalityRaw,
        @Nullable @JsonProperty("expires_at") String expiresAtRaw,
        @Nullable @JsonProperty("evidenceKind") String evidenceKindRaw,
        @Nullable String evidenceExcerpt
) {
}

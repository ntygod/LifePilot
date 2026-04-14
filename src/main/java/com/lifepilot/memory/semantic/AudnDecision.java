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
 * @param operation  操作类型
 * @param entityName 实体名称
 * @param entityType 实体类型
 * @param description 实体描述（ADD/UPDATE 时使用）
 * @param properties 实体属性键值对（ADD/UPDATE 时使用）
 * @param extractionConfidence LLM 提取置信度 [0.0, 1.0]，可能为 null
 * @param importanceScore 信息重要性评分 [0.0, 1.0]，可能为 null
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
        @Nullable @JsonAlias({"importance", "importance_score"}) Float importanceScore
) {}

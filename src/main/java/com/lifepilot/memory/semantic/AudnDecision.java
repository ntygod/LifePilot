package com.lifepilot.memory.semantic;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * AUDN 决策结果 — LLM 结构化输出的单条实体操作决策。
 *
 * <p>每条决策描述对一个实体的操作：新增、更新、删除或跳过。
 * 由 {@link RealtimeExtractor} 通过 LLM 结构化输出获取。</p>
 *
 * @param operation  操作类型
 * @param entityName 实体名称
 * @param entityType 实体类型
 * @param description 实体描述（ADD/UPDATE 时使用）
 * @param properties 实体属性键值对（ADD/UPDATE 时使用）
 * @author zsg
 * @since 2026-03-05
 */
public record AudnDecision(
        AudnOperation operation,
        String entityName,
        EntityType entityType,
        @Nullable String description,
        @Nullable Map<String, Object> properties
) {}

package com.lifepilot.tool.model;

import com.lifepilot.tool.schema.JsonSchema;
import jakarta.annotation.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * 类型安全的工具输入。
 *
 * <p>所有工具输入在执行前必须通过 JSON Schema 校验。
 * 校验失败时返回结构化错误信息，使 LLM 能够理解错误并修正参数。</p>
 *
 * @param toolId 目标工具 ID
 * @param parameters 参数 Map
 * @param schema 输入参数的 JSON Schema
 * @param idempotencyKey 幂等键（可选）
 * @param context 请求级上下文（可选），用于传递 sessionId 等非 LLM 参数
 * @author zsg
 * @since 2026-02-24
 */
public record ToolInput(
        String toolId,
        Map<String, Object> parameters,
        JsonSchema schema,
        @Nullable String idempotencyKey,
        @Nullable Map<String, Object> context
) {
    /** 校验参数是否符合 Schema。 */
    public ValidationResult validate() {
        var errors = schema.validate(parameters);
        if (errors.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.failed(errors);
    }

    /** 获取参数值（类型安全）。 */
    @SuppressWarnings("unchecked")
    public <T> T getParam(String name, Class<T> type) {
        Object value = parameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException("缺少必需参数: " + name);
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "参数类型不匹配: %s 期望 %s，实际 %s"
                            .formatted(name, type.getSimpleName(),
                                    value.getClass().getSimpleName()));
        }
        return (T) value;
    }

    /** 获取可选参数值。 */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptionalParam(String name, Class<T> type) {
        Object value = parameters.get(name);
        if (value == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        return Optional.of((T) value);
    }

    /** 从 context 中获取指定类型的值。 */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getContextValue(String key, Class<T> type) {
        if (context == null) return Optional.empty();
        Object value = context.get(key);
        if (value == null || !type.isInstance(value)) return Optional.empty();
        return Optional.of((T) value);
    }
}

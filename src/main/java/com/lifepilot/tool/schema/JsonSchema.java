package com.lifepilot.tool.schema;

import com.lifepilot.tool.model.ValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量 JSON Schema 校验。
 *
 * <p>不引入第三方 JSON Schema 库，仅覆盖 required 字段检查
 * 和基础类型检查（string/integer/number/boolean/object/array）。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class JsonSchema {

    /** Schema 定义（Map 表示）。 */
    private final Map<String, Object> schema;

    public JsonSchema(Map<String, Object> schema) {
        this.schema = schema != null ? Map.copyOf(schema) : Map.of();
    }

    /** 创建空 Schema（不校验任何参数）。 */
    public static JsonSchema empty() {
        return new JsonSchema(Map.of());
    }

    /** 从 Map 创建 Schema。 */
    public static JsonSchema of(Map<String, Object> schema) {
        return new JsonSchema(schema);
    }

    /**
     * 校验参数是否符合 Schema。
     *
     * @param parameters 待校验的参数
     * @return 校验错误列表（空列表表示通过）
     */
    @SuppressWarnings("unchecked")
    public List<ValidationError> validate(Map<String, Object> parameters) {
        var errors = new ArrayList<ValidationError>();

        if (schema.isEmpty()) {
            return errors;
        }

        // 检查 required 字段
        if (schema.containsKey("required")) {
            var required = (List<String>) schema.get("required");
            for (String field : required) {
                if (!parameters.containsKey(field) || parameters.get(field) == null) {
                    errors.add(ValidationError.of(field, "缺少必需参数"));
                }
            }
        }

        // 检查字段类型
        if (schema.containsKey("properties")) {
            var properties = (Map<String, Object>) schema.get("properties");
            for (var entry : properties.entrySet()) {
                String field = entry.getKey();
                Object value = parameters.get(field);
                if (value == null) {
                    continue; // 非 required 字段可以为空
                }
                if (entry.getValue() instanceof Map<?, ?> propSchema) {
                    String expectedType = (String) propSchema.get("type");
                    if (expectedType != null && !matchesType(value, expectedType)) {
                        errors.add(ValidationError.typeMismatch(
                                field, expectedType, value.getClass().getSimpleName()));
                    }
                }
            }
        }

        return errors;
    }

    /** 检查值是否匹配指定的 JSON Schema 类型。 */
    private boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            default -> true; // 未知类型不校验
        };
    }

    /** 获取 Schema 定义。 */
    public Map<String, Object> toMap() {
        return schema;
    }

    /** 获取 required 字段集合。 */
    @SuppressWarnings("unchecked")
    public Set<String> requiredFields() {
        if (schema.containsKey("required")) {
            return Set.copyOf((List<String>) schema.get("required"));
        }
        return Set.of();
    }
}

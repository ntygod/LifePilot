package com.lifepilot.tool.schema;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.tool.model.ValidationError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
     * 根据 Schema 对参数做类型强转。
     *
     * <p>LLM 经常将整数传为字符串（如 {@code "5"} 而非 {@code 5}），
     * 或将数组/对象传为 JSON 字符串。此方法根据 Schema 定义的类型自动尝试转换，
     * 转换失败则保留原值，由后续 {@link #validate} 报错。</p>
     *
     * @param parameters 原始参数
     * @return 强转后的参数副本（不修改原 Map）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> coerceParameters(Map<String, Object> parameters) {
        if (schema.isEmpty() || !schema.containsKey("properties")) {
            return parameters;
        }

        var properties = (Map<String, Object>) schema.get("properties");
        var result = new LinkedHashMap<>(parameters);
        boolean changed = false;

        for (var entry : properties.entrySet()) {
            String field = entry.getKey();
            Object value = result.get(field);
            if (value == null) {
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> propSchema) {
                String expectedType = (String) propSchema.get("type");
                if (expectedType != null && !matchesType(value, expectedType)) {
                    Object coerced = tryCoerce(value, expectedType);
                    if (coerced != value) {
                        result.put(field, coerced);
                        changed = true;
                    }
                }
            }
        }

        return changed ? Map.copyOf(result) : parameters;
    }

    /**
     * 尝试将值强转为目标 JSON Schema 类型。
     *
     * @param value 原始值
     * @param targetType 目标类型名
     * @return 转换后的值，转换失败则返回原值
     */
    @SuppressWarnings("unchecked")
    private Object tryCoerce(Object value, String targetType) {
        if (!(value instanceof String str)) {
            return value;
        }
        try {
            return switch (targetType) {
                case "integer" -> {
                    // 去除首尾空格后尝试解析
                    String trimmed = str.strip();
                    yield trimmed.contains(".") ? Long.parseLong(trimmed.split("\\.")[0])
                            : Long.parseLong(trimmed);
                }
                case "number" -> Double.parseDouble(str.strip());
                case "boolean" -> switch (str.strip().toLowerCase()) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> value; // 无法转换，保留原值
                };
                case "array" -> {
                    String trimmed = str.strip();
                    if (trimmed.startsWith("[")) {
                        yield OBJECT_MAPPER.readValue(trimmed, List.class);
                    }
                    yield value;
                }
                case "object" -> {
                    String trimmed = str.strip();
                    if (trimmed.startsWith("{")) {
                        yield OBJECT_MAPPER.readValue(trimmed, Map.class);
                    }
                    yield value;
                }
                default -> value;
            };
        } catch (NumberFormatException | JsonProcessingException _) {
            return value; // 转换失败，保留原值交给 validate 报错
        }
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
                    // format 关键字检查
                    String format = (String) propSchema.get("format");
                    if (format != null && value instanceof String strValue) {
                        if (!matchesFormat(strValue, format)) {
                            errors.add(ValidationError.formatMismatch(field, format, strValue));
                        }
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

    /** 检查字符串值是否匹配指定的 JSON Schema format。 */
    private boolean matchesFormat(String value, String format) {
        try {
            return switch (format) {
                case "date-time" -> {
                    // 使用 ISO_DATE_TIME 解析，兼容带/不带时区偏移的格式
                    java.time.format.DateTimeFormatter.ISO_DATE_TIME.parse(value);
                    yield true;
                }
                case "date" -> {
                    java.time.LocalDate.parse(value);
                    yield true;
                }
                case "time" -> {
                    java.time.LocalTime.parse(value);
                    yield true;
                }
                default -> true; // 未知 format 不校验
            };
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /** 获取 Schema 定义。 */
    @JsonValue
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

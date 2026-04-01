package com.lifepilot.tool.schema;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonSchema.coerceParameters 参数类型强转测试。
 *
 * <p>验证 LLM 传入的字符串值能否被正确强转为 Schema 期望的类型，
 * 以及转换失败时保留原值的行为。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
class JsonSchema_参数类型强转测试 {

    // ==================== 字符串 → 整数 ====================

    @Test
    void 字符串转整数_正整数() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> result = schema.coerceParameters(Map.of("count", "5"));

        assertEquals(5L, result.get("count"));
    }

    @Test
    void 字符串转整数_负整数() {
        JsonSchema schema = schemaWithProperty("offset", "integer");

        Map<String, Object> result = schema.coerceParameters(Map.of("offset", "-3"));

        assertEquals(-3L, result.get("offset"));
    }

    @Test
    void 字符串转整数_带小数点的整数值() {
        // "5.0" 应截取整数部分解析为 5
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> result = schema.coerceParameters(Map.of("count", "5.0"));

        assertEquals(5L, result.get("count"));
    }

    @Test
    void 字符串转整数_零() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> result = schema.coerceParameters(Map.of("count", "0"));

        assertEquals(0L, result.get("count"));
    }

    @Test
    void 字符串转整数_带空格() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> result = schema.coerceParameters(Map.of("count", "  42  "));

        assertEquals(42L, result.get("count"));
    }

    @Test
    void 字符串转整数_非数字字符串保留原值() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> result = schema.coerceParameters(Map.of("count", "abc"));

        assertEquals("abc", result.get("count"));
    }

    @Test
    void 字符串转整数_空字符串保留原值() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> result = schema.coerceParameters(Map.of("count", ""));

        assertEquals("", result.get("count"));
    }

    // ==================== 字符串 → 数字 ====================

    @Test
    void 字符串转数字_浮点数() {
        JsonSchema schema = schemaWithProperty("price", "number");

        Map<String, Object> result = schema.coerceParameters(Map.of("price", "3.14"));

        assertEquals(3.14, result.get("price"));
    }

    @Test
    void 字符串转数字_负浮点数() {
        JsonSchema schema = schemaWithProperty("temperature", "number");

        Map<String, Object> result = schema.coerceParameters(Map.of("temperature", "-0.5"));

        assertEquals(-0.5, result.get("temperature"));
    }

    @Test
    void 字符串转数字_整数形式() {
        JsonSchema schema = schemaWithProperty("score", "number");

        Map<String, Object> result = schema.coerceParameters(Map.of("score", "100"));

        assertEquals(100.0, result.get("score"));
    }

    @Test
    void 字符串转数字_带空格() {
        JsonSchema schema = schemaWithProperty("val", "number");

        Map<String, Object> result = schema.coerceParameters(Map.of("val", "  2.718  "));

        assertEquals(2.718, result.get("val"));
    }

    @Test
    void 字符串转数字_非数字字符串保留原值() {
        JsonSchema schema = schemaWithProperty("price", "number");

        Map<String, Object> result = schema.coerceParameters(Map.of("price", "not-a-number"));

        assertEquals("not-a-number", result.get("price"));
    }

    // ==================== 字符串 → 布尔 ====================

    @Test
    void 字符串转布尔_true小写() {
        JsonSchema schema = schemaWithProperty("active", "boolean");

        Map<String, Object> result = schema.coerceParameters(Map.of("active", "true"));

        assertEquals(Boolean.TRUE, result.get("active"));
    }

    @Test
    void 字符串转布尔_false小写() {
        JsonSchema schema = schemaWithProperty("active", "boolean");

        Map<String, Object> result = schema.coerceParameters(Map.of("active", "false"));

        assertEquals(Boolean.FALSE, result.get("active"));
    }

    @Test
    void 字符串转布尔_大写TRUE() {
        JsonSchema schema = schemaWithProperty("active", "boolean");

        Map<String, Object> result = schema.coerceParameters(Map.of("active", "TRUE"));

        assertEquals(Boolean.TRUE, result.get("active"));
    }

    @Test
    void 字符串转布尔_混合大小写False() {
        JsonSchema schema = schemaWithProperty("active", "boolean");

        Map<String, Object> result = schema.coerceParameters(Map.of("active", "False"));

        assertEquals(Boolean.FALSE, result.get("active"));
    }

    @Test
    void 字符串转布尔_带空格() {
        JsonSchema schema = schemaWithProperty("active", "boolean");

        Map<String, Object> result = schema.coerceParameters(Map.of("active", "  true  "));

        assertEquals(Boolean.TRUE, result.get("active"));
    }

    @Test
    void 字符串转布尔_无法识别的值保留原值() {
        JsonSchema schema = schemaWithProperty("active", "boolean");

        Map<String, Object> result = schema.coerceParameters(Map.of("active", "yes"));

        assertEquals("yes", result.get("active"));
    }

    // ==================== 字符串 → 数组 ====================

    @Test
    void 字符串转数组_JSON数组() {
        JsonSchema schema = schemaWithProperty("tags", "array");

        Map<String, Object> result = schema.coerceParameters(Map.of("tags", "[1,2,3]"));

        assertEquals(List.of(1, 2, 3), result.get("tags"));
    }

    @Test
    void 字符串转数组_字符串元素() {
        JsonSchema schema = schemaWithProperty("names", "array");

        Map<String, Object> result = schema.coerceParameters(
                Map.of("names", "[\"alice\",\"bob\"]"));

        assertEquals(List.of("alice", "bob"), result.get("names"));
    }

    @Test
    void 字符串转数组_空数组() {
        JsonSchema schema = schemaWithProperty("items", "array");

        Map<String, Object> result = schema.coerceParameters(Map.of("items", "[]"));

        assertEquals(List.of(), result.get("items"));
    }

    @Test
    void 字符串转数组_带空格() {
        JsonSchema schema = schemaWithProperty("tags", "array");

        Map<String, Object> result = schema.coerceParameters(Map.of("tags", "  [1, 2]  "));

        assertEquals(List.of(1, 2), result.get("tags"));
    }

    @Test
    void 字符串转数组_非数组格式保留原值() {
        JsonSchema schema = schemaWithProperty("tags", "array");

        Map<String, Object> result = schema.coerceParameters(Map.of("tags", "not-array"));

        assertEquals("not-array", result.get("tags"));
    }

    @Test
    void 字符串转数组_非法JSON保留原值() {
        JsonSchema schema = schemaWithProperty("tags", "array");

        Map<String, Object> result = schema.coerceParameters(Map.of("tags", "[invalid json"));

        assertEquals("[invalid json", result.get("tags"));
    }

    // ==================== 字符串 → 对象 ====================

    @Test
    void 字符串转对象_JSON对象() {
        JsonSchema schema = schemaWithProperty("config", "object");

        Map<String, Object> result = schema.coerceParameters(
                Map.of("config", "{\"key\":\"value\"}"));

        assertEquals(Map.of("key", "value"), result.get("config"));
    }

    @Test
    void 字符串转对象_空对象() {
        JsonSchema schema = schemaWithProperty("meta", "object");

        Map<String, Object> result = schema.coerceParameters(Map.of("meta", "{}"));

        assertEquals(Map.of(), result.get("meta"));
    }

    @Test
    void 字符串转对象_带空格() {
        JsonSchema schema = schemaWithProperty("config", "object");

        Map<String, Object> result = schema.coerceParameters(
                Map.of("config", "  {\"a\": 1}  "));

        assertEquals(Map.of("a", 1), result.get("config"));
    }

    @Test
    void 字符串转对象_非对象格式保留原值() {
        JsonSchema schema = schemaWithProperty("config", "object");

        Map<String, Object> result = schema.coerceParameters(Map.of("config", "not-object"));

        assertEquals("not-object", result.get("config"));
    }

    @Test
    void 字符串转对象_非法JSON保留原值() {
        JsonSchema schema = schemaWithProperty("config", "object");

        Map<String, Object> result = schema.coerceParameters(Map.of("config", "{broken"));

        assertEquals("{broken", result.get("config"));
    }

    // ==================== 空 Schema / 无 properties ====================

    @Test
    void 空Schema_不做任何转换() {
        JsonSchema schema = JsonSchema.empty();

        Map<String, Object> params = Map.of("count", "5");
        Map<String, Object> result = schema.coerceParameters(params);

        assertSame(params, result, "空 Schema 应直接返回原始 Map 引用");
        assertEquals("5", result.get("count"));
    }

    @Test
    void 无properties的Schema_不做任何转换() {
        JsonSchema schema = JsonSchema.of(Map.of("required", List.of("count")));

        Map<String, Object> params = Map.of("count", "5");
        Map<String, Object> result = schema.coerceParameters(params);

        assertSame(params, result, "无 properties 时应直接返回原始 Map 引用");
    }

    // ==================== 已是正确类型 ====================

    @Test
    void 已是正确的整数类型_不变() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> params = Map.of("count", 42);
        Map<String, Object> result = schema.coerceParameters(params);

        assertSame(params, result, "无需转换时应返回原始 Map 引用");
        assertEquals(42, result.get("count"));
    }

    @Test
    void 已是正确的Long类型_不变() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> params = Map.of("count", 100L);
        Map<String, Object> result = schema.coerceParameters(params);

        assertSame(params, result);
        assertEquals(100L, result.get("count"));
    }

    @Test
    void 已是正确的字符串类型_不变() {
        JsonSchema schema = schemaWithProperty("name", "string");

        Map<String, Object> params = Map.of("name", "hello");
        Map<String, Object> result = schema.coerceParameters(params);

        assertSame(params, result);
    }

    @Test
    void 已是正确的布尔类型_不变() {
        JsonSchema schema = schemaWithProperty("active", "boolean");

        Map<String, Object> params = Map.of("active", true);
        Map<String, Object> result = schema.coerceParameters(params);

        assertSame(params, result);
    }

    @Test
    void 已是正确的List类型_不变() {
        JsonSchema schema = schemaWithProperty("tags", "array");

        Map<String, Object> params = Map.of("tags", List.of(1, 2, 3));
        Map<String, Object> result = schema.coerceParameters(params);

        assertSame(params, result);
    }

    @Test
    void 已是正确的Map类型_不变() {
        JsonSchema schema = schemaWithProperty("config", "object");

        Map<String, Object> params = Map.of("config", Map.of("key", "val"));
        Map<String, Object> result = schema.coerceParameters(params);

        assertSame(params, result);
    }

    // ==================== null 值 ====================

    @Test
    void null值_不处理() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        // 使用 HashMap 因为 Map.of 不允许 null 值
        var params = new HashMap<String, Object>();
        params.put("count", null);

        Map<String, Object> result = schema.coerceParameters(params);

        assertNull(result.get("count"));
    }

    // ==================== schema 中没有的字段 ====================

    @Test
    void schema中没有的字段_透传不处理() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> result = schema.coerceParameters(
                Map.of("count", "5", "extra", "should-stay"));

        assertEquals(5L, result.get("count"));
        assertEquals("should-stay", result.get("extra"));
    }

    // ==================== 多字段同时转换 ====================

    @Test
    void 多字段同时强转() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "count", Map.of("type", "integer"),
                        "price", Map.of("type", "number"),
                        "active", Map.of("type", "boolean")
                )
        ));

        Map<String, Object> result = schema.coerceParameters(Map.of(
                "count", "10",
                "price", "9.99",
                "active", "true"
        ));

        assertEquals(10L, result.get("count"));
        assertEquals(9.99, result.get("price"));
        assertEquals(Boolean.TRUE, result.get("active"));
    }

    @Test
    void 部分字段需要转换_部分已是正确类型() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "count", Map.of("type", "integer"),
                        "name", Map.of("type", "string")
                )
        ));

        Map<String, Object> result = schema.coerceParameters(Map.of(
                "count", "7",
                "name", "hello"
        ));

        assertEquals(7L, result.get("count"));
        assertEquals("hello", result.get("name"));
    }

    // ==================== 不修改原 Map ====================

    @Test
    void 转换后不修改原始参数Map() {
        JsonSchema schema = schemaWithProperty("count", "integer");

        var original = new LinkedHashMap<String, Object>();
        original.put("count", "5");

        Map<String, Object> result = schema.coerceParameters(original);

        assertEquals("5", original.get("count"), "原始 Map 不应被修改");
        assertEquals(5L, result.get("count"), "返回的 Map 应包含转换后的值");
    }

    // ==================== 非字符串的错误类型不转换 ====================

    @Test
    void 非字符串的错误类型值_不尝试转换() {
        // 传入 List 但 schema 期望 integer，由于不是 String 不会尝试转换
        JsonSchema schema = schemaWithProperty("count", "integer");

        Map<String, Object> result = schema.coerceParameters(Map.of("count", List.of(1)));

        // tryCoerce 只处理 String，非 String 返回原值
        assertEquals(List.of(1), result.get("count"));
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建包含单个字段的 Schema。
     */
    private JsonSchema schemaWithProperty(String fieldName, String type) {
        return JsonSchema.of(Map.of(
                "properties", Map.of(fieldName, Map.of("type", type))
        ));
    }
}

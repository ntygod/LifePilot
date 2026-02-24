package com.lifepilot.tool.schema;

import com.lifepilot.tool.model.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonSchema 校验测试。
 *
 * @author zsg
 * @since 2026-02-24
 */
class JsonSchemaTest {

    @Test
    void 空Schema_任何参数通过() {
        JsonSchema schema = JsonSchema.empty();
        List<ValidationError> errors = schema.validate(Map.of("any", "value"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void 必需字段缺失_校验失败() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "required", List.of("name", "age")
        ));
        List<ValidationError> errors = schema.validate(Map.of("name", "test"));
        assertEquals(1, errors.size());
        assertEquals("age", errors.getFirst().path());
    }

    @Test
    void 类型不匹配_校验失败() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "count", Map.of("type", "integer")
                )
        ));
        // 传入 String 而非 Integer
        List<ValidationError> errors = schema.validate(Map.of("count", "not-a-number"));
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().message().contains("类型不匹配"));
    }

    @Test
    void 类型匹配_校验通过() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "required", List.of("name"),
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "count", Map.of("type", "integer"),
                        "active", Map.of("type", "boolean")
                )
        ));
        List<ValidationError> errors = schema.validate(Map.of(
                "name", "test", "count", 42, "active", true
        ));
        assertTrue(errors.isEmpty());
    }

    @Test
    void 可选字段为空_校验通过() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "required", List.of("name"),
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "optional", Map.of("type", "string")
                )
        ));
        List<ValidationError> errors = schema.validate(Map.of("name", "test"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void requiredFields_返回必需字段集合() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "required", List.of("a", "b")
        ));
        assertEquals(2, schema.requiredFields().size());
        assertTrue(schema.requiredFields().contains("a"));
    }

    @Test
    void number类型_接受所有数值() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "properties", Map.of("val", Map.of("type", "number"))
        ));
        assertTrue(schema.validate(Map.of("val", 3.14)).isEmpty());
        assertTrue(schema.validate(Map.of("val", 42)).isEmpty());
        assertTrue(schema.validate(Map.of("val", 100L)).isEmpty());
    }
}

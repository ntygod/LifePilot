package com.lifepilot.tool.schema;

import com.lifepilot.tool.model.ValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonSchema format 校验缺失探索测试。
 *
 * <p>本测试编码的是修复后的期望行为（expected behavior）。</p>
 *
 * <p>修复前（Bug Condition C1）：</p>
 * <ul>
 *   <li>{@code JsonSchema.validate()} 仅做 required 和基础类型检查</li>
 *   <li>不支持 JSON Schema {@code "format"} 关键字</li>
 *   <li>非法格式字符串（如 {@code "not-a-date"}、{@code "明天上午9点"}）通过校验</li>
 * </ul>
 *
 * <p>修复后（Expected Behavior）：</p>
 * <ul>
 *   <li>{@code validate()} 检查 {@code "format"} 关键字</li>
 *   <li>非法格式字符串返回包含 formatMismatch 的 {@code ValidationError}</li>
 *   <li>错误消息包含"格式不匹配"</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("JsonSchema format 校验缺失探索测试")
class JsonSchemaFormat_BugCondition_探索测试 {

    /**
     * 构造声明了 {@code "format": "date-time"} 的 schema，传入非法字符串，
     * 断言 {@code validate()} 返回非空错误列表。
     *
     * <p><b>Validates: Requirements 1.1, 2.1</b></p>
     */
    @Test
    void validate_对非法dateTime格式返回错误() {
        // 构造声明了 format: date-time 的 schema
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "startTime", Map.of("type", "string", "format", "date-time")
                ),
                "required", List.of("startTime")
        ));

        // 传入明显非法的字符串
        Map<String, Object> params = Map.of("startTime", "not-a-date");
        List<ValidationError> errors = schema.validate(params);

        // 期望：返回非空错误列表，包含格式不匹配信息
        assertFalse(errors.isEmpty(),
                "非法 date-time 字符串 'not-a-date' 应被 format 校验拦截，返回非空错误列表");
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("格式不匹配")),
                "错误消息应包含'格式不匹配'");
    }

    /**
     * 传入中文自然语言时间字符串，断言 {@code validate()} 返回格式错误。
     *
     * <p><b>Validates: Requirements 1.1, 2.1</b></p>
     */
    @Test
    void validate_对中文自然语言时间返回错误() {
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "startTime", Map.of("type", "string", "format", "date-time")
                ),
                "required", List.of("startTime")
        ));

        Map<String, Object> params = Map.of("startTime", "明天上午9点");
        List<ValidationError> errors = schema.validate(params);

        assertFalse(errors.isEmpty(),
                "中文自然语言时间 '明天上午9点' 应被 format 校验拦截");
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("格式不匹配")),
                "错误消息应包含'格式不匹配'");
    }

    /**
     * 传入格式错误的 ISO 8601 字符串（非法月份/时间），断言 {@code validate()} 返回格式错误。
     *
     * <p><b>Validates: Requirements 1.1, 2.1</b></p>
     */
    @Test
    void validate_对非法ISO8601字符串返回错误() {
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "startTime", Map.of("type", "string", "format", "date-time")
                ),
                "required", List.of("startTime")
        ));

        // 非法月份 13、非法时间 99:99:99
        Map<String, Object> params = Map.of("startTime", "2025-13-45T99:99:99");
        List<ValidationError> errors = schema.validate(params);

        assertFalse(errors.isEmpty(),
                "非法 ISO 8601 字符串 '2025-13-45T99:99:99' 应被 format 校验拦截");
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("格式不匹配")),
                "错误消息应包含'格式不匹配'");
    }

    /**
     * 对 {@code "format": "date"} 传入非法日期字符串，断言返回格式错误。
     *
     * <p><b>Validates: Requirements 1.1, 2.1</b></p>
     */
    @Test
    void validate_对非法date格式返回错误() {
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "dueDate", Map.of("type", "string", "format", "date")
                ),
                "required", List.of("dueDate")
        ));

        Map<String, Object> params = Map.of("dueDate", "next-friday");
        List<ValidationError> errors = schema.validate(params);

        assertFalse(errors.isEmpty(),
                "非法日期字符串 'next-friday' 应被 format 校验拦截");
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("格式不匹配")),
                "错误消息应包含'格式不匹配'");
    }

    /**
     * 对 {@code "format": "time"} 传入非法时间字符串，断言返回格式错误。
     *
     * <p><b>Validates: Requirements 1.1, 2.1</b></p>
     */
    @Test
    void validate_对非法time格式返回错误() {
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "targetTime", Map.of("type", "string", "format", "time")
                ),
                "required", List.of("targetTime")
        ));

        Map<String, Object> params = Map.of("targetTime", "25:99");
        List<ValidationError> errors = schema.validate(params);

        assertFalse(errors.isEmpty(),
                "非法时间字符串 '25:99' 应被 format 校验拦截");
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("格式不匹配")),
                "错误消息应包含'格式不匹配'");
    }
}

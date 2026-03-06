package com.lifepilot.tool.schema;

import com.lifepilot.tool.model.ValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonSchema format 校验保持测试（Preservation Property）。
 *
 * <p>验证合法 format 字符串和无 format 声明的字段不受修复影响。</p>
 *
 * <p>在未修复代码上，{@code validate()} 不检查 format 关键字，
 * 所有字符串都通过校验（包括合法和非法的）。这些测试验证：
 * 修复后合法字符串仍然通过校验（行为保持不变）。</p>
 *
 * <p><b>Validates: Requirements 3.1</b></p>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("JsonSchema format 校验保持测试")
class JsonSchemaFormat_Preservation_保持测试 {

    /**
     * 对声明了 {@code "format": "date-time"} 的字段传入合法 ISO 8601 OffsetDateTime 字符串，
     * {@code validate()} 返回空错误列表。
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     */
    @Test
    void validate_合法dateTime字符串返回空错误列表() {
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "startTime", Map.of("type", "string", "format", "date-time")
                ),
                "required", List.of("startTime")
        ));

        Map<String, Object> params = Map.of("startTime", "2026-01-16T09:00:00+08:00");
        List<ValidationError> errors = schema.validate(params);

        assertTrue(errors.isEmpty(),
                "合法 ISO 8601 date-time '2026-01-16T09:00:00+08:00' 应通过校验，返回空错误列表");
    }

    /**
     * 对声明了 {@code "format": "date"} 的字段传入合法 ISO 8601 日期字符串，
     * {@code validate()} 返回空错误列表。
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     */
    @Test
    void validate_合法date字符串返回空错误列表() {
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "dueDate", Map.of("type", "string", "format", "date")
                ),
                "required", List.of("dueDate")
        ));

        Map<String, Object> params = Map.of("dueDate", "2026-01-16");
        List<ValidationError> errors = schema.validate(params);

        assertTrue(errors.isEmpty(),
                "合法 ISO 8601 date '2026-01-16' 应通过校验，返回空错误列表");
    }

    /**
     * 对声明了 {@code "format": "time"} 的字段传入合法时间字符串（HH:mm 和 HH:mm:ss），
     * {@code validate()} 返回空错误列表。
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     */
    @Test
    void validate_合法time字符串返回空错误列表() {
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "targetTime", Map.of("type", "string", "format", "time")
                ),
                "required", List.of("targetTime")
        ));

        // HH:mm 格式
        Map<String, Object> paramsShort = Map.of("targetTime", "09:00");
        List<ValidationError> errorsShort = schema.validate(paramsShort);
        assertTrue(errorsShort.isEmpty(),
                "合法时间 '09:00' 应通过校验，返回空错误列表");

        // HH:mm:ss 格式
        Map<String, Object> paramsFull = Map.of("targetTime", "09:00:00");
        List<ValidationError> errorsFull = schema.validate(paramsFull);
        assertTrue(errorsFull.isEmpty(),
                "合法时间 '09:00:00' 应通过校验，返回空错误列表");
    }

    /**
     * 对未声明 format 的 string 字段传入任意字符串，
     * {@code validate()} 返回空错误列表（行为不变）。
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     */
    @Test
    void validate_无format声明的string字段传入任意字符串返回空错误列表() {
        var schema = JsonSchema.of(Map.of(
                "properties", Map.of(
                        "name", Map.of("type", "string")
                ),
                "required", List.of("name")
        ));

        // 普通字符串
        Map<String, Object> params1 = Map.of("name", "任意字符串内容");
        List<ValidationError> errors1 = schema.validate(params1);
        assertTrue(errors1.isEmpty(),
                "无 format 声明的 string 字段传入普通字符串应通过校验");

        // 看起来像日期但字段未声明 format
        Map<String, Object> params2 = Map.of("name", "not-a-date");
        List<ValidationError> errors2 = schema.validate(params2);
        assertTrue(errors2.isEmpty(),
                "无 format 声明的 string 字段传入 'not-a-date' 应通过校验");

        // 空字符串
        Map<String, Object> params3 = Map.of("name", "");
        List<ValidationError> errors3 = schema.validate(params3);
        assertTrue(errors3.isEmpty(),
                "无 format 声明的 string 字段传入空字符串应通过校验");
    }
}

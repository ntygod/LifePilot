package com.lifepilot.tool.model;

import jakarta.annotation.Nullable;

/**
 * 单个校验错误。
 *
 * @param path 字段路径（如 "parameters.date"）
 * @param message 错误描述
 * @param expectedType 期望的类型（可选）
 * @param actualValue 实际值（可选，脱敏后）
 * @author zsg
 * @since 2026-02-24
 */
public record ValidationError(
        String path,
        String message,
        @Nullable String expectedType,
        @Nullable String actualValue
) {
    /** 创建简单校验错误。 */
    public static ValidationError of(String path, String message) {
        return new ValidationError(path, message, null, null);
    }

    /** 创建类型不匹配错误。 */
    public static ValidationError typeMismatch(String path, String expected, String actual) {
        return new ValidationError(path,
                "类型不匹配: 期望 %s，实际 %s".formatted(expected, actual),
                expected, actual);
    }

}

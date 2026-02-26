package com.lifepilot.sandbox.model;

/**
 * 代码预检违规项。
 *
 * @param pattern     匹配的危险模式
 * @param description 违规描述
 * @param severity    严重程度（CRITICAL / HIGH / MEDIUM）
 * @param lineNumber  违规所在行号
 * @author zsg
 * @since 2026-03-01
 */
public record Violation(
        String pattern,
        String description,
        String severity,
        int lineNumber
) {}

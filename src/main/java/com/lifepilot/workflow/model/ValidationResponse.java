package com.lifepilot.workflow.model;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * YAML 校验响应。
 *
 * @param valid    是否有效
 * @param errors   错误列表
 * @param warnings 警告列表
 * @param dagValid DAG 是否有效（无环）
 * @param dagError DAG 错误信息
 * @author zsg
 * @since 2026-03-13
 */
public record ValidationResponse(
        boolean valid,
        List<String> errors,
        List<String> warnings,
        boolean dagValid,
        @Nullable String dagError
) {
    public ValidationResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

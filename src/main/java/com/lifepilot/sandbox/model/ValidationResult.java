package com.lifepilot.sandbox.model;

import java.util.List;

/**
 * 代码预检结果。
 *
 * @param passed     是否通过预检
 * @param violations 违规项列表
 * @author zsg
 * @since 2026-03-01
 */
public record ValidationResult(
        boolean passed,
        List<Violation> violations
) {

    /**
     * 创建通过结果。
     *
     * @return 通过的预检结果（无违规项）
     */
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    /**
     * 创建失败结果。
     *
     * @param violations 违规项列表
     * @return 失败的预检结果
     */
    public static ValidationResult rejected(List<Violation> violations) {
        return new ValidationResult(false, List.copyOf(violations));
    }
}

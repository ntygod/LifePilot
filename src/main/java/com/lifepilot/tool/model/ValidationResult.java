package com.lifepilot.tool.model;

import java.util.List;

/**
 * 参数校验结果 — sealed interface 穷举成功和失败。
 *
 * @author zsg
 * @since 2026-02-24
 */
public sealed interface ValidationResult {

    /** 校验通过。 */
    record Ok() implements ValidationResult {}

    /**
     * 校验失败。
     *
     * @param errors 错误列表
     */
    record Failed(List<ValidationError> errors) implements ValidationResult {
        public Failed {
            errors = List.copyOf(errors);
        }

        /** 格式化错误信息（供 LLM 理解）。 */
        public String formatForLlm() {
            var sb = new StringBuilder("参数校验失败:\n");
            for (var error : errors) {
                sb.append("  - ").append(error.path())
                        .append(": ").append(error.message()).append("\n");
            }
            return sb.toString();
        }
    }

    /** 创建成功结果。 */
    static ValidationResult ok() {
        return new Ok();
    }

    /** 创建失败结果。 */
    static ValidationResult failed(List<ValidationError> errors) {
        return new Failed(errors);
    }

    /** 判断是否校验通过。 */
    default boolean isValid() {
        return this instanceof Ok;
    }
}

package com.lifepilot.workflow.expression;

import com.lifepilot.workflow.model.WorkflowException.ExpressionException;

/**
 * 表达式求值运行时异常，包装 {@link ExpressionException} record。
 *
 * <p>由于 {@link ExpressionException} 是 sealed interface 的 record 实现（非 Java Exception），
 * 需要通过此运行时异常在执行流中传播错误。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class ExpressionEvaluationException extends RuntimeException {

    private final ExpressionException detail;

    public ExpressionEvaluationException(ExpressionException detail) {
        super(detail.message());
        this.detail = detail;
    }

    /**
     * 获取表达式异常详情 record。
     */
    public ExpressionException detail() {
        return detail;
    }
}

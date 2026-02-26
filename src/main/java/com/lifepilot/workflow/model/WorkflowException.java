package com.lifepilot.workflow.model;

import java.util.List;

/**
 * 工作流异常类型层次。
 *
 * <p>使用 sealed interface + record 实现（非 Exception 继承层次），
 * 作为错误值类型在 {@link Result} 中返回或在需要中断执行流时抛出。
 *
 * <ul>
 *   <li>{@link ParseException} — YAML 解析错误，通过 {@link Result} 返回</li>
 *   <li>{@link ExpressionException} — 表达式求值错误</li>
 *   <li>{@link ExecutionException} — 步骤执行错误，包装底层异常</li>
 *   <li>{@link StateTransitionException} — 无效状态转换</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public sealed interface WorkflowException {

    /**
     * YAML 解析错误，包含一个或多个错误描述。
     *
     * @param errors 错误描述列表
     */
    record ParseException(List<String> errors) implements WorkflowException {}

    /**
     * 表达式求值错误。
     *
     * @param expression 出错的表达式
     * @param message    错误描述
     * @param position   错误在表达式中的位置（字符偏移量）
     */
    record ExpressionException(String expression, String message, int position) implements WorkflowException {}

    /**
     * 步骤执行错误，包装底层异常原因。
     *
     * @param stepId  出错的步骤 ID
     * @param message 错误描述
     * @param cause   底层异常
     */
    record ExecutionException(String stepId, String message, Throwable cause) implements WorkflowException {}

    /**
     * 无效状态转换错误。
     *
     * @param from 当前状态
     * @param to   目标状态
     */
    record StateTransitionException(WorkflowState from, WorkflowState to) implements WorkflowException {}
}

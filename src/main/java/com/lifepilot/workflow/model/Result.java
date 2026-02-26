package com.lifepilot.workflow.model;

/**
 * 通用结果类型，表示操作的成功或失败。
 *
 * <p>使用 sealed interface + record 实现，替代异常驱动的错误处理。
 * 典型用法：{@code WorkflowYamlParser.parse()} 返回
 * {@code Result<WorkflowDefinition, List<String>>}。
 *
 * @param <T> 成功值类型
 * @param <E> 错误值类型
 * @author zsg
 * @since 2026-02-26
 */
public sealed interface Result<T, E> {

    /**
     * 操作成功，携带结果值。
     *
     * @param value 成功值
     * @param <T>   成功值类型
     * @param <E>   错误值类型
     */
    record Ok<T, E>(T value) implements Result<T, E> {}

    /**
     * 操作失败，携带错误信息。
     *
     * @param error 错误值
     * @param <T>   成功值类型
     * @param <E>   错误值类型
     */
    record Err<T, E>(E error) implements Result<T, E> {}
}

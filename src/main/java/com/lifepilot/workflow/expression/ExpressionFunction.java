package com.lifepilot.workflow.expression;

import java.util.List;

/**
 * 表达式函数接口，定义可在表达式引擎中调用的函数契约。
 *
 * <p>函数接收参数列表，返回计算结果。参数类型校验由各函数实现自行负责，
 * 类型不匹配时应抛出 {@link ExpressionEvaluationException}。
 *
 * @author zsg
 * @since 2026-03-13
 */
@FunctionalInterface
public interface ExpressionFunction {

    /**
     * 执行函数逻辑。
     *
     * @param args 函数参数列表
     * @return 函数计算结果
     * @throws ExpressionEvaluationException 参数类型不匹配或执行错误时抛出
     */
    Object apply(List<Object> args);
}

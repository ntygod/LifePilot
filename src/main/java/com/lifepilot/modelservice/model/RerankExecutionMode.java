package com.lifepilot.modelservice.model;

/**
 * 精排执行模式。
 *
 * @author zsg
 * @since 2026-03-24
 */
public enum RerankExecutionMode {
    /** 关闭精排。 */
    DISABLED,
    /** 使用原生精排服务。 */
    NATIVE,
    /** 使用 LLM 逐条评分。 */
    LLM_POINTWISE,
    /** 使用 LLM 批量排序。 */
    LLM_LISTWISE
}

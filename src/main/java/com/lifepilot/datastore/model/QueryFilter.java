package com.lifepilot.datastore.model;

/**
 * 查询过滤条件 — 描述单个字段的过滤约束。
 *
 * <p>由 QueryEngine 解析为参数化 SQL WHERE 子句。
 * 多个 QueryFilter 之间以 AND 逻辑组合。</p>
 *
 * @param field 字段名称
 * @param op    过滤操作符
 * @param value 过滤值
 * @author zsg
 * @since 2026-03-10
 */
public record QueryFilter(
        String field,
        FilterOp op,
        Object value
) {
}

package com.lifepilot.datastore.model;

/**
 * SQL 语句与参数封装。
 *
 * <p>由 QueryEngine 和 AggregationEngine 生成，
 * 包含参数化 SQL 语句和对应的绑定参数数组。</p>
 *
 * @param sql    参数化 SQL 语句（使用 ? 占位符）
 * @param params 绑定参数数组
 * @author zsg
 * @since 2026-03-10
 */
public record SqlWithParams(
        String sql,
        Object[] params
) {
}

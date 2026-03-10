package com.lifepilot.datastore.model;

/**
 * 聚合函数枚举。
 *
 * <p>定义时序聚合查询中可用的聚合函数，
 * 由 AggregationEngine 映射为对应的 SQL 聚合函数。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public enum AggregateFunction {

    /** 求和。 */
    SUM,

    /** 平均值。 */
    AVG,

    /** 最小值。 */
    MIN,

    /** 最大值。 */
    MAX,

    /** 计数。 */
    COUNT
}

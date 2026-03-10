package com.lifepilot.datastore.model;

/**
 * 过滤操作枚举。
 *
 * <p>定义查询过滤条件中的比较操作符，
 * 由 QueryEngine 映射为对应的 SQL 操作符。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public enum FilterOp {

    /** 等于。 */
    EQ("="),

    /** 不等于。 */
    NE("!="),

    /** 大于。 */
    GT(">"),

    /** 大于等于。 */
    GTE(">="),

    /** 小于。 */
    LT("<"),

    /** 小于等于。 */
    LTE("<="),

    /** 包含（LIKE 模糊匹配）。 */
    CONTAINS("LIKE"),

    /** 在集合中（IN 操作）。 */
    IN("IN");

    private final String sqlOperator;

    FilterOp(String sqlOperator) {
        this.sqlOperator = sqlOperator;
    }

    /**
     * 获取对应的 SQL 操作符。
     *
     * @return SQL 操作符字符串
     */
    public String toSql() {
        return sqlOperator;
    }
}

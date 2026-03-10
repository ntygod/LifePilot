package com.lifepilot.datastore.model;

/**
 * 时间粒度枚举。
 *
 * <p>定义时序聚合查询中的时间分组粒度，
 * 由 AggregationEngine 映射为对应的 SQLite strftime 格式。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public enum TimeGranularity {

    /** 按天分组。 */
    DAY("'%Y-%m-%d'"),

    /** 按周分组。 */
    WEEK("'%Y-W%W'"),

    /** 按月分组。 */
    MONTH("'%Y-%m'");

    private final String strftimeFormat;

    TimeGranularity(String strftimeFormat) {
        this.strftimeFormat = strftimeFormat;
    }

    /**
     * 获取对应的 SQLite strftime 格式字符串。
     *
     * @return strftime 格式
     */
    public String toStrftime() {
        return strftimeFormat;
    }
}

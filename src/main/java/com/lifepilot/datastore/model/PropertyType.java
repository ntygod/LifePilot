package com.lifepilot.datastore.model;

/**
 * 属性类型枚举。
 *
 * <p>定义集合属性定义中的值类型，用于文档写入时的类型校验
 * 和 Generated Column 的 SQLite 列亲和性映射。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public enum PropertyType {

    /** 文本。 */
    TEXT,

    /** 数值。 */
    NUMBER,

    /** 布尔值。 */
    BOOLEAN,

    /** 日期（ISO 8601 日期格式）。 */
    DATE,

    /** 日期时间（ISO 8601 日期时间格式）。 */
    DATETIME,

    /** 单选（字符串值）。 */
    SELECT,

    /** 多选（字符串数组）。 */
    MULTI_SELECT,

    /** URL 地址。 */
    URL,

    /** JSON 对象或数组。 */
    JSON;

    /**
     * 判断该类型是否支持 Generated Column 索引。
     *
     * <p>MULTI_SELECT 和 JSON 类型的值为数组/对象，不适合 B-tree 索引。</p>
     *
     * @return 是否可索引
     */
    public boolean isIndexable() {
        return this != MULTI_SELECT && this != JSON;
    }

    /**
     * 获取对应的 SQLite 列亲和性。
     *
     * @return SQLite 列亲和性字符串，不可索引类型返回 null
     */
    public String toSqliteAffinity() {
        return switch (this) {
            case TEXT, DATE, DATETIME, SELECT, URL -> "TEXT";
            case NUMBER -> "REAL";
            case BOOLEAN -> "INTEGER";
            case MULTI_SELECT, JSON -> null;
        };
    }
}

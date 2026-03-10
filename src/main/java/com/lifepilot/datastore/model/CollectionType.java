package com.lifepilot.datastore.model;

/**
 * 集合类型枚举。
 *
 * <p>定义数据集合的用途分类，不同类型决定可用的操作：
 * DOCUMENT 支持结构化查询，NOTE 支持全文搜索，METRIC 支持时序聚合。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public enum CollectionType {

    /** 结构化列表（书单、购物清单、联系人）。 */
    DOCUMENT,

    /** 非结构化笔记（日记、灵感），支持 FTS5 全文搜索。 */
    NOTE,

    /** 时序指标（体重、运动量），支持时间范围聚合查询。 */
    METRIC
}

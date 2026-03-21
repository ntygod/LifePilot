package com.lifepilot.datastore.model;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * 查询请求 — 封装文档查询的完整参数。
 *
 * <p>包含集合 ID、过滤条件列表、排序字段和方向、分页偏移和限制。
 * 由 QueryEngine 转换为参数化 SQL。</p>
 *
 * @param collectionId  目标集合 ID
 * @param filters       过滤条件列表
 * @param sortField     排序字段（可选）
 * @param sortDirection 排序方向（可选）
 * @param offset        分页偏移
 * @param limit         每页数量
 * @author zsg
 * @since 2026-03-10
 */
public record QueryRequest(
        String collectionId,
        List<QueryFilter> filters,
        @Nullable String sortField,
        @Nullable SortDirection sortDirection,
        int offset,
        int limit,
        @Nullable String startTime,
        @Nullable String endTime
) {

    public QueryRequest(String collectionId,
                        List<QueryFilter> filters,
                        @Nullable String sortField,
                        @Nullable SortDirection sortDirection,
                        int offset,
                        int limit) {
        this(collectionId, filters, sortField, sortDirection, offset, limit, null, null);
    }
}

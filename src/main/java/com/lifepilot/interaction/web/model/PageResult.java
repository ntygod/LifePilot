package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 分页结果包装。
 *
 * @param items 当前页数据列表
 * @param page  当前页码（从 0 开始）
 * @param size  每页大小
 * @param total 总记录数
 * @param <T>   数据类型
 * @author zsg
 * @since 2026-02-27
 */
public record PageResult<T>(
        List<T> items,
        int page,
        int size,
        long total
) {
    /** 紧凑构造器，防御性拷贝 items 列表。 */
    public PageResult {
        items = List.copyOf(items);
    }
}

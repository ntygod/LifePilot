package com.lifepilot.meta.infra.browser;

import java.util.List;

/**
 * 一次 interactive element 扫描结果。
 *
 * @param elements  按发现顺序编号的元素列表
 * @param total     元素总数（可能 &gt; elements.size() 如果被截断）
 * @param viewport  [width, height]
 * @param truncated 是否达到 maxElements 被截断
 *
 * @author zsg
 * @since 2026-04-24
 */
public record IndexedSnapshot(
        List<IndexedElement> elements,
        int total,
        int[] viewport,
        boolean truncated
) {}

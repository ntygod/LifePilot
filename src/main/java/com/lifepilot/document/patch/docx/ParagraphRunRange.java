package com.lifepilot.document.patch.docx;

/**
 * 文本锚点在段落中的范围定位 —— 段落索引 + 起止 run 下标 + run 内字符偏移。
 *
 * <p>例：一段有 3 个 run ["付款期限 ", "30", " 天"]，target="期限 30"，
 * 返回 {@code paragraphIndex=?, startRunIndex=0, startCharOffset=2, endRunIndex=1, endCharOffset=2}。
 * 其中 endCharOffset 是"结束位置的下一个索引"（开区间），便于后续 substring。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public record ParagraphRunRange(
        int paragraphIndex,
        int startRunIndex,
        int startCharOffset,
        int endRunIndex,
        int endCharOffset
) {}

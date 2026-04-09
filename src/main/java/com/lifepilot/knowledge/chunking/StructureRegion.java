package com.lifepilot.knowledge.chunking;

import java.util.List;

/**
 * 结构区域 — 连续同类型行的分组。
 *
 * @param type              区域结构类型
 * @param startOffset       在原始文本中的起始偏移量
 * @param endOffset         在原始文本中的结束偏移量
 * @param content           区域文本内容
 * @param lines             包含的标注行
 * @param headingHierarchy  当前标题层级上下文
 * @author zsg
 * @since 2026-04-09
 */
public record StructureRegion(
        StructureType type,
        int startOffset,
        int endOffset,
        String content,
        List<AnnotatedLine> lines,
        List<String> headingHierarchy
) {
    public StructureRegion {
        lines = List.copyOf(lines);
        headingHierarchy = List.copyOf(headingHierarchy);
    }

    /** 区域文本长度。 */
    public int length() {
        return content.length();
    }
}

package com.lifepilot.knowledge.chunking;

/**
 * 行标注 — 单行文本及其结构分类。
 *
 * @param lineNumber  行号（0-based）
 * @param startOffset 在原始文本中的起始偏移量
 * @param endOffset   在原始文本中的结束偏移量
 * @param type        结构类型
 * @param rawText     原始行文本
 * @author zsg
 * @since 2026-04-09
 */
public record AnnotatedLine(
        int lineNumber,
        int startOffset,
        int endOffset,
        StructureType type,
        String rawText
) {}

package com.lifepilot.knowledge.chunking;

import org.springframework.lang.Nullable;

/**
 * 行标注 — 单行文本及其结构分类。
 *
 * <p>初始构造时 type 可为 null（尚未分类），经 classifyLines 填充后保证非 null。
 *
 * @param lineNumber  行号（0-based）
 * @param startOffset 在原始文本中的起始偏移量
 * @param endOffset   在原始文本中的结束偏移量
 * @param type        结构类型（初始可为 null，分类完成后非 null）
 * @param rawText     原始行文本
 * @author zsg
 * @since 2026-04-09
 */
public record AnnotatedLine(
        int lineNumber,
        int startOffset,
        int endOffset,
        @Nullable StructureType type,
        String rawText
) {}

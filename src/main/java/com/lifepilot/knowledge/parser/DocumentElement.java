package com.lifepilot.knowledge.parser;

import java.util.List;
import java.util.Optional;

/**
 * 文档结构元素 — 解析器提取的文档内容单元。
 *
 * <p>每个元素携带在原始文档中的起止偏移量，用于后续分块时保留位置信息。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface DocumentElement
        permits DocumentElement.Heading, DocumentElement.Paragraph,
                DocumentElement.Table, DocumentElement.CodeBlock,
                DocumentElement.ListBlock, DocumentElement.Image {

    /** 元素在原始文档中的起始偏移量。 */
    int startOffset();

    /** 元素在原始文档中的结束偏移量。 */
    int endOffset();

    /**
     * 标题元素。
     *
     * @param level       标题级别（1-6）
     * @param text        标题文本
     * @param startOffset 起始偏移量
     * @param endOffset   结束偏移量
     */
    record Heading(int level, String text, int startOffset, int endOffset)
            implements DocumentElement {}

    /**
     * 段落元素。
     *
     * @param text        段落文本
     * @param startOffset 起始偏移量
     * @param endOffset   结束偏移量
     */
    record Paragraph(String text, int startOffset, int endOffset)
            implements DocumentElement {}

    /**
     * 表格元素。
     *
     * @param headers     表头列表
     * @param rows        数据行列表
     * @param startOffset 起始偏移量
     * @param endOffset   结束偏移量
     */
    record Table(List<String> headers, List<List<String>> rows,
                 int startOffset, int endOffset) implements DocumentElement {}

    /**
     * 代码块元素。
     *
     * @param language    编程语言标识（可选）
     * @param code        代码内容
     * @param startOffset 起始偏移量
     * @param endOffset   结束偏移量
     */
    record CodeBlock(Optional<String> language, String code,
                     int startOffset, int endOffset) implements DocumentElement {}

    /**
     * 列表元素。
     *
     * @param ordered     是否为有序列表
     * @param items       列表项
     * @param startOffset 起始偏移量
     * @param endOffset   结束偏移量
     */
    record ListBlock(boolean ordered, List<String> items,
                     int startOffset, int endOffset) implements DocumentElement {}

    /**
     * 图片元素。
     *
     * @param altText     替代文本（可选）
     * @param caption     图片说明（可选）
     * @param startOffset 起始偏移量
     * @param endOffset   结束偏移量
     */
    record Image(Optional<String> altText, Optional<String> caption,
                 int startOffset, int endOffset) implements DocumentElement {}
}

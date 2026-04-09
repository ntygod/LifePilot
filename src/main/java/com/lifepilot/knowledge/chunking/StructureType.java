package com.lifepilot.knowledge.chunking;

/**
 * 文档结构类型 — 行级别的结构分类。
 *
 * @author zsg
 * @since 2026-04-09
 */
public enum StructureType {
    /** 标题行（Markdown ATX 或纯文本短行标题）。 */
    HEADING,
    /** 普通段落文本。 */
    PARAGRAPH,
    /** 代码块（围栏或缩进）。 */
    CODE,
    /** 列表项（有序或无序）。 */
    LIST,
    /** 表格行。 */
    TABLE,
    /** 空行（段落分隔符）。 */
    BLANK
}

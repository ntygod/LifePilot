package com.lifepilot.knowledge.parser;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档解析器 sealed interface — 定义文档解析的统一契约。
 *
 * <p>当前仅 permit {@link MarkdownParser} 和 {@link PlainTextParser}，
 * 后续 spec 可扩展 permits 列表添加 PdfParser、WordParser 等实现。
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface DocumentParser
        permits MarkdownParser, PlainTextParser, PdfParser, WordParser {

    /**
     * 返回此解析器支持的文件扩展名列表（不含点号）。
     *
     * @return 支持的扩展名列表，如 ["md", "markdown"]
     */
    List<String> supportedExtensions();

    /**
     * 解析指定文件，返回解析结果。
     *
     * @param filePath 文件路径
     * @return 解析结果
     * @throws DocumentParseException 解析失败时抛出
     */
    ParseResult parse(Path filePath) throws DocumentParseException;

    /**
     * 提取指定文件的元数据。
     *
     * @param filePath 文件路径
     * @return 文档元数据
     */
    DocumentMetadata extractMetadata(Path filePath);

    /**
     * 判断此解析器是否能解析指定文件（基于扩展名匹配）。
     *
     * @param filePath 文件路径
     * @return 如果文件扩展名匹配则返回 true
     */
    default boolean canParse(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        return supportedExtensions().stream()
                .anyMatch(ext -> fileName.endsWith("." + ext));
    }
}

package com.lifepilot.knowledge.parser;

import java.nio.file.Path;
import java.util.List;

/**
 * Word/DOCX 文档解析器 — 基于 Apache POI。
 *
 * <p>按段落提取文本，通过样式识别标题层级，完整提取表格结构。
 * 仅支持 DOCX（Office 2007+），旧版 .doc 抛出 {@link DocumentParseException}。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class WordParser implements DocumentParser {

    private static final List<String> EXTENSIONS = List.of("docx");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        // TODO: Task 2.3 实现
        throw new UnsupportedOperationException("WordParser 尚未实现");
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        // TODO: Task 2.3 实现
        throw new UnsupportedOperationException("WordParser 尚未实现");
    }
}

package com.lifepilot.knowledge.parser;

import java.nio.file.Path;
import java.util.List;

/**
 * PDF 文档解析器 — 基于 Apache PDFBox 3.x。
 *
 * <p>逐页提取文本，支持部分解析（单页失败跳过），提取元数据。
 * 加密 PDF 抛出 {@link DocumentParseException}(Phase.FORMAT_DECODE)。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class PdfParser implements DocumentParser {

    private static final List<String> EXTENSIONS = List.of("pdf");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        // TODO: Task 2.1 实现
        throw new UnsupportedOperationException("PdfParser 尚未实现");
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        // TODO: Task 2.1 实现
        throw new UnsupportedOperationException("PdfParser 尚未实现");
    }
}

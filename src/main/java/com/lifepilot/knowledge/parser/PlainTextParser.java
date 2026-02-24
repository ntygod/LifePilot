package com.lifepilot.knowledge.parser;

import java.nio.file.Path;
import java.util.List;

/**
 * 纯文本文档解析器（桩实现）。
 *
 * <p>支持 txt、text、log、csv、tsv 扩展名。完整实现将在后续任务 7.2 中完成。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class PlainTextParser implements DocumentParser {

    @Override
    public List<String> supportedExtensions() {
        return List.of("txt", "text", "log", "csv", "tsv");
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        // 桩实现，后续任务 7.2 替换
        throw new UnsupportedOperationException("PlainTextParser 尚未实现");
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        // 桩实现，后续任务 7.2 替换
        throw new UnsupportedOperationException("PlainTextParser 尚未实现");
    }
}

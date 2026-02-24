package com.lifepilot.knowledge.parser;

import java.nio.file.Path;
import java.util.List;

/**
 * Markdown 文档解析器（桩实现）。
 *
 * <p>支持 md、markdown、mkd 扩展名。完整实现将在后续任务 7.1 中完成。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class MarkdownParser implements DocumentParser {

    @Override
    public List<String> supportedExtensions() {
        return List.of("md", "markdown", "mkd");
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        // 桩实现，后续任务 7.1 替换
        throw new UnsupportedOperationException("MarkdownParser 尚未实现");
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        // 桩实现，后续任务 7.1 替换
        throw new UnsupportedOperationException("MarkdownParser 尚未实现");
    }
}

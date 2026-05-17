package com.lifepilot.document.parser;

import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.ParseResult;
import com.lifepilot.knowledge.parser.PlainTextParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DocumentParserService} 的路由行为测试。
 *
 * @author zsg
 * @since 2026-04-20
 */
class DocumentParserService_文档解析路由测试 {

    @Test
    void 按扩展名命中对应解析器(@TempDir Path tmp) throws IOException {
        DocumentParserService service = new DocumentParserService(
                List.of(new MarkdownParser(), new PlainTextParser()));

        Path md = tmp.resolve("hello.md");
        Files.writeString(md, "# 标题\n\n正文");

        ParseResult result = service.parse(md);

        assertThat(result.text()).contains("标题");
        assertThat(result.text()).contains("正文");
    }

    @Test
    void 不支持的扩展名抛出异常(@TempDir Path tmp) throws IOException {
        DocumentParserService service = new DocumentParserService(
                List.of(new MarkdownParser()));

        Path bin = tmp.resolve("data.bin");
        Files.writeString(bin, "binary");

        assertThatThrownBy(() -> service.parse(bin))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("bin");
    }

    @Test
    void supports_报告是否能解析(@TempDir Path tmp) throws IOException {
        DocumentParserService service = new DocumentParserService(
                List.of(new MarkdownParser()));

        Path md = tmp.resolve("a.md");
        Path bin = tmp.resolve("a.bin");
        Files.createFile(md);
        Files.createFile(bin);

        assertThat(service.supports(md)).isTrue();
        assertThat(service.supports(bin)).isFalse();
    }
}

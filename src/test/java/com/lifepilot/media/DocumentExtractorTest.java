package com.lifepilot.media;

import com.lifepilot.knowledge.parser.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentExtractor 单元测试。
 *
 * 覆盖各格式提取、不支持格式异常以及解析器异常包装。
 *
 * @author zsg
 * @since 2026-07-01
 */
class DocumentExtractorTest {

    private DocumentExtractor extractor;

    @BeforeEach
    void setUp() {
        // 直接复用知识库模块中的解析器实现
        var markdownParser = new MarkdownParser();
        var plainTextParser = new PlainTextParser();
        var pdfParser = new PdfParser();
        var wordParser = new WordParser();

        extractor = new DocumentExtractor(List.of(
                markdownParser,
                plainTextParser,
                pdfParser,
                wordParser
        ));
    }

    @Test
    void textMarkdown_使用Markdown解析器提取文本() {
        String content = "# 标题\n\n这里是 Markdown 正文。";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(data, "text/markdown", "doc.md");

        assertNotNull(text);
        assertTrue(text.contains("标题"));
        assertTrue(text.contains("Markdown 正文"));
    }

    @Test
    void textPlain_使用PlainText解析器提取文本() {
        String content = "第一行\n第二行\n第三行";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(data, "text/plain", "note.txt");

        assertNotNull(text);
        assertTrue(text.contains("第一行"));
        assertTrue(text.contains("第二行"));
        assertTrue(text.contains("第三行"));
    }

    @Test
    void 不支持的mimeType_抛出DocumentExtractionException() {
        byte[] data = "dummy".getBytes(StandardCharsets.UTF_8);

        DocumentExtractionException ex = assertThrows(
                DocumentExtractionException.class,
                () -> extractor.extract(data, "application/zip", "archive.zip")
        );

        assertTrue(ex.getMessage().contains("不支持的文档格式"));
    }

    @Test
    void 解析器抛出DocumentParseException时被包装为DocumentExtractionException() {
        // application/pdf → pdf 扩展名 → PdfParser
        byte[] invalidPdfData = new byte[] { 0x00, 0x01, 0x02, 0x03 };

        DocumentExtractionException ex = assertThrows(
                DocumentExtractionException.class,
                () -> extractor.extract(invalidPdfData, "application/pdf", "broken.pdf")
        );

        assertNotNull(ex.getCause());
        assertInstanceOf(DocumentParseException.class, ex.getCause());
    }
}


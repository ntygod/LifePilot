package com.lifepilot.knowledge.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FormatDetector 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class FormatDetectorTest {

    private FormatDetector detector;
    private MarkdownParser markdownParser;
    private PlainTextParser plainTextParser;

    @BeforeEach
    void setUp() {
        markdownParser = new MarkdownParser();
        plainTextParser = new PlainTextParser();
        detector = new FormatDetector(List.of(markdownParser, plainTextParser));
    }

    // --- Markdown 扩展名路由 ---

    @Test
    void detect_md扩展名返回MarkdownParser() {
        Optional<DocumentParser> result = detector.detect(Path.of("test.md"));
        assertTrue(result.isPresent());
        assertInstanceOf(MarkdownParser.class, result.get());
    }

    @Test
    void detect_markdown扩展名返回MarkdownParser() {
        Optional<DocumentParser> result = detector.detect(Path.of("test.markdown"));
        assertTrue(result.isPresent());
        assertInstanceOf(MarkdownParser.class, result.get());
    }

    @Test
    void detect_mkd扩展名返回MarkdownParser() {
        Optional<DocumentParser> result = detector.detect(Path.of("test.mkd"));
        assertTrue(result.isPresent());
        assertInstanceOf(MarkdownParser.class, result.get());
    }

    // --- PlainText 扩展名路由 ---

    @Test
    void detect_txt扩展名返回PlainTextParser() {
        Optional<DocumentParser> result = detector.detect(Path.of("test.txt"));
        assertTrue(result.isPresent());
        assertInstanceOf(PlainTextParser.class, result.get());
    }

    @Test
    void detect_text扩展名返回PlainTextParser() {
        Optional<DocumentParser> result = detector.detect(Path.of("test.text"));
        assertTrue(result.isPresent());
        assertInstanceOf(PlainTextParser.class, result.get());
    }

    @Test
    void detect_log扩展名返回PlainTextParser() {
        Optional<DocumentParser> result = detector.detect(Path.of("test.log"));
        assertTrue(result.isPresent());
        assertInstanceOf(PlainTextParser.class, result.get());
    }

    @Test
    void detect_csv扩展名返回PlainTextParser() {
        Optional<DocumentParser> result = detector.detect(Path.of("data.csv"));
        assertTrue(result.isPresent());
        assertInstanceOf(PlainTextParser.class, result.get());
    }

    @Test
    void detect_tsv扩展名返回PlainTextParser() {
        Optional<DocumentParser> result = detector.detect(Path.of("data.tsv"));
        assertTrue(result.isPresent());
        assertInstanceOf(PlainTextParser.class, result.get());
    }

    // --- 不支持的扩展名 ---

    @Test
    void detect_pdf扩展名返回empty() {
        Optional<DocumentParser> result = detector.detect(Path.of("doc.pdf"));
        assertTrue(result.isEmpty());
    }

    @Test
    void detect_docx扩展名返回empty() {
        Optional<DocumentParser> result = detector.detect(Path.of("doc.docx"));
        assertTrue(result.isEmpty());
    }

    @Test
    void detect_xyz扩展名返回empty() {
        Optional<DocumentParser> result = detector.detect(Path.of("file.xyz"));
        assertTrue(result.isEmpty());
    }

    // --- supportedExtensions ---

    @Test
    void supportedExtensions_返回所有解析器扩展名的并集() {
        List<String> extensions = detector.supportedExtensions();

        // Markdown: md, markdown, mkd
        assertTrue(extensions.contains("md"));
        assertTrue(extensions.contains("markdown"));
        assertTrue(extensions.contains("mkd"));

        // PlainText: txt, text, log, csv, tsv
        assertTrue(extensions.contains("txt"));
        assertTrue(extensions.contains("text"));
        assertTrue(extensions.contains("log"));
        assertTrue(extensions.contains("csv"));
        assertTrue(extensions.contains("tsv"));

        assertEquals(8, extensions.size());
    }
}

package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.parser.DocumentParseException.Phase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlainTextParser 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class PlainTextParserTest {

    private PlainTextParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new PlainTextParser();
    }

    // --- 段落识别 ---

    @Test
    void 段落识别_按空行分隔() {
        Path file = Path.of("src/test/resources/knowledge/sample.txt");
        ParseResult result = parser.parse(file);

        List<DocumentElement.Paragraph> paragraphs = result.elements().stream()
                .filter(e -> e instanceof DocumentElement.Paragraph)
                .map(e -> (DocumentElement.Paragraph) e)
                .toList();

        assertEquals(3, paragraphs.size());
        assertTrue(paragraphs.get(0).text().contains("第一个段落"));
        assertTrue(paragraphs.get(1).text().contains("第二个段落"));
        assertTrue(paragraphs.get(2).text().contains("第三个段落"));
    }

    // --- 行尾规范化 ---

    @Test
    void 行尾规范化_CRLF和CR统一为LF() throws IOException {
        // 创建包含混合行尾的临时文件
        Path mixedFile = tempDir.resolve("mixed-endings.txt");
        byte[] content = "第一行\r\n第二行\r第三行\n第四行".getBytes(StandardCharsets.UTF_8);
        Files.write(mixedFile, content);

        ParseResult result = parser.parse(mixedFile);

        assertFalse(result.text().contains("\r"), "规范化后不应包含 \\r");
        assertTrue(result.text().contains("第一行\n第二行\n第三行\n第四行"));
    }

    // --- 编码检测：UTF-8 ---

    @Test
    void 编码检测_UTF8正常文件() {
        Path file = Path.of("src/test/resources/knowledge/sample.txt");
        ParseResult result = parser.parse(file);

        assertNotNull(result.text());
        assertFalse(result.text().isEmpty());
        assertTrue(result.text().contains("第一个段落"));
    }

    // --- 编码检测：UTF-8 BOM ---

    @Test
    void 编码检测_UTF8BOM文件() throws IOException {
        // 编程方式创建带 BOM 的临时文件
        Path bomFile = tempDir.resolve("bom-test.txt");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] textBytes = "BOM 测试内容".getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[bom.length + textBytes.length];
        System.arraycopy(bom, 0, combined, 0, bom.length);
        System.arraycopy(textBytes, 0, combined, bom.length, textBytes.length);
        Files.write(bomFile, combined);

        ParseResult result = parser.parse(bomFile);

        assertNotNull(result.text());
        // BOM 应被跳过，不出现在文本中
        assertFalse(result.text().startsWith("\uFEFF"), "BOM 字符不应出现在文本开头");
        assertTrue(result.text().contains("BOM 测试内容"));
    }

    // --- 元数据 ---

    @Test
    void 元数据_文件名作为标题_字数为正() {
        Path file = Path.of("src/test/resources/knowledge/sample.txt");
        ParseResult result = parser.parse(file);
        DocumentMetadata metadata = result.metadata();

        assertTrue(metadata.title().isPresent());
        assertEquals("sample", metadata.title().get());
        assertTrue(metadata.wordCount() > 0, "字数应为正数");
    }

    // --- 文件不存在异常 ---

    @Test
    void 文件不存在_抛出DocumentParseException_FILE_READ() {
        Path nonExistent = Path.of("src/test/resources/knowledge/not-exist.txt");

        DocumentParseException ex = assertThrows(DocumentParseException.class,
                () -> parser.parse(nonExistent));

        assertEquals(Phase.FILE_READ, ex.getPhase());
        assertTrue(ex.getFilePath().contains("not-exist.txt"));
    }

    // --- 空文件 ---

    @Test
    void 空文件_返回空元素列表() throws IOException {
        Path emptyFile = tempDir.resolve("empty.txt");
        Files.writeString(emptyFile, "");

        ParseResult result = parser.parse(emptyFile);

        assertTrue(result.elements().isEmpty(), "空文件应返回空元素列表");
        assertTrue(result.warnings().isEmpty(), "warnings 应为空列表");
    }

    // --- warnings 为空 ---

    @Test
    void 正常解析_warnings为空列表() {
        Path file = Path.of("src/test/resources/knowledge/sample.txt");
        ParseResult result = parser.parse(file);

        assertNotNull(result.warnings());
        assertTrue(result.warnings().isEmpty());
    }
}

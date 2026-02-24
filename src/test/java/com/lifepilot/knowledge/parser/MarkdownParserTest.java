package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.parser.DocumentParseException.Phase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MarkdownParser 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class MarkdownParserTest {

    private MarkdownParser parser;

    @BeforeEach
    void setUp() {
        parser = new MarkdownParser();
    }

    // --- ATX 标题解析 ---

    @Test
    void 解析ATX标题_提取正确级别和文本() {
        Path file = Path.of("src/test/resources/knowledge/sample.md");
        ParseResult result = parser.parse(file);

        List<DocumentElement.Heading> headings = result.elements().stream()
                .filter(e -> e instanceof DocumentElement.Heading)
                .map(e -> (DocumentElement.Heading) e)
                .toList();

        // sample.md 包含 # 一级标题、## 二级标题、### 三级标题
        assertTrue(headings.size() >= 3, "应至少包含 3 个标题");

        // 验证级别
        assertEquals(1, headings.get(0).level());
        assertEquals("一级标题", headings.get(0).text());

        assertEquals(2, headings.get(1).level());
        assertEquals("二级标题", headings.get(1).text());

        assertEquals(3, headings.get(2).level());
        assertEquals("三级标题", headings.get(2).text());
    }

    // --- 围栏代码块解析 ---

    @Test
    void 解析围栏代码块_提取语言标识和代码内容() {
        Path file = Path.of("src/test/resources/knowledge/sample.md");
        ParseResult result = parser.parse(file);

        List<DocumentElement.CodeBlock> codeBlocks = result.elements().stream()
                .filter(e -> e instanceof DocumentElement.CodeBlock)
                .map(e -> (DocumentElement.CodeBlock) e)
                .toList();

        assertEquals(1, codeBlocks.size());
        assertTrue(codeBlocks.get(0).language().isPresent());
        assertEquals("java", codeBlocks.get(0).language().get());
        assertTrue(codeBlocks.get(0).code().contains("Hello, World!"));
    }

    @Test
    void 解析围栏代码块_无语言标识时language为empty() {
        // 构造一个无语言标识的代码块内容
        String content = "# Title\n\n```\nplain code\n```\n";
        try {
            var tempFile = java.nio.file.Files.createTempFile("test-md-", ".md");
            java.nio.file.Files.writeString(tempFile, content);
            ParseResult result = parser.parse(tempFile);

            List<DocumentElement.CodeBlock> codeBlocks = result.elements().stream()
                    .filter(e -> e instanceof DocumentElement.CodeBlock)
                    .map(e -> (DocumentElement.CodeBlock) e)
                    .toList();

            assertEquals(1, codeBlocks.size());
            assertTrue(codeBlocks.get(0).language().isEmpty());
            java.nio.file.Files.deleteIfExists(tempFile);
        } catch (java.io.IOException e) {
            fail("临时文件操作失败: " + e.getMessage());
        }
    }

    // --- GFM 表格解析 ---

    @Test
    void 解析GFM表格_提取表头和数据行() {
        Path file = Path.of("src/test/resources/knowledge/sample.md");
        ParseResult result = parser.parse(file);

        List<DocumentElement.Table> tables = result.elements().stream()
                .filter(e -> e instanceof DocumentElement.Table)
                .map(e -> (DocumentElement.Table) e)
                .toList();

        assertEquals(1, tables.size());
        DocumentElement.Table table = tables.get(0);

        assertEquals(List.of("名称", "类型", "描述"), table.headers());
        assertEquals(2, table.rows().size());
        assertEquals(List.of("id", "TEXT", "主键"), table.rows().get(0));
        assertEquals(List.of("name", "TEXT", "名称"), table.rows().get(1));
    }

    // --- YAML Front Matter 解析 ---

    @Test
    void 解析FrontMatter_提取title和author和date和lang() {
        Path file = Path.of("src/test/resources/knowledge/sample.md");
        ParseResult result = parser.parse(file);
        DocumentMetadata metadata = result.metadata();

        assertTrue(metadata.title().isPresent());
        assertEquals("测试文档", metadata.title().get());

        assertTrue(metadata.author().isPresent());
        assertEquals("zsg", metadata.author().get());

        assertTrue(metadata.createdAt().isPresent());
        assertEquals(Instant.parse("2026-01-15T00:00:00Z"), metadata.createdAt().get());

        assertTrue(metadata.language().isPresent());
        assertEquals("zh-CN", metadata.language().get());
    }

    // --- 元数据优先级 ---

    @Test
    void 元数据优先级_FrontMatter的title优先于第一个标题() {
        Path file = Path.of("src/test/resources/knowledge/sample.md");
        ParseResult result = parser.parse(file);

        // Front Matter 中有 title: 测试文档，应优先使用
        assertEquals("测试文档", result.metadata().title().orElse(""));
    }

    @Test
    void 元数据回退_无FrontMatter时使用第一个标题作为title() {
        Path file = Path.of("src/test/resources/knowledge/no-frontmatter.md");
        ParseResult result = parser.parse(file);

        assertTrue(result.metadata().title().isPresent());
        assertEquals("无 Front Matter 的文档", result.metadata().title().get());
    }

    // --- 元素排序 ---

    @Test
    void 元素按startOffset升序排列() {
        Path file = Path.of("src/test/resources/knowledge/sample.md");
        ParseResult result = parser.parse(file);

        List<DocumentElement> elements = result.elements();
        for (int i = 1; i < elements.size(); i++) {
            assertTrue(elements.get(i).startOffset() >= elements.get(i - 1).startOffset(),
                    "元素 " + i + " 的 startOffset 应 >= 元素 " + (i - 1) + " 的 startOffset");
        }
    }

    // --- 文件不存在异常 ---

    @Test
    void 文件不存在_抛出DocumentParseException_FILE_READ() {
        Path nonExistent = Path.of("src/test/resources/knowledge/not-exist.md");

        DocumentParseException ex = assertThrows(DocumentParseException.class,
                () -> parser.parse(nonExistent));

        assertEquals(Phase.FILE_READ, ex.getPhase());
        assertTrue(ex.getFilePath().contains("not-exist.md"));
    }

    // --- 代码块内标题不被提取 ---

    @Test
    void 代码块内的标题不被提取为Heading元素() {
        String content = "# Real Title\n\n```markdown\n# Fake Title Inside Code\n```\n";
        try {
            var tempFile = java.nio.file.Files.createTempFile("test-md-", ".md");
            java.nio.file.Files.writeString(tempFile, content);
            ParseResult result = parser.parse(tempFile);

            List<DocumentElement.Heading> headings = result.elements().stream()
                    .filter(e -> e instanceof DocumentElement.Heading)
                    .map(e -> (DocumentElement.Heading) e)
                    .toList();

            assertEquals(1, headings.size(), "代码块内的标题不应被提取");
            assertEquals("Real Title", headings.get(0).text());
            java.nio.file.Files.deleteIfExists(tempFile);
        } catch (java.io.IOException e) {
            fail("临时文件操作失败: " + e.getMessage());
        }
    }
}

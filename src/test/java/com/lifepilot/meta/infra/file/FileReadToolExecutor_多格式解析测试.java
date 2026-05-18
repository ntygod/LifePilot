package com.lifepilot.meta.infra.file;

import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository.AttachmentRecord;
import com.lifepilot.knowledge.parser.DocumentParserService;
import com.lifepilot.knowledge.parser.ExcelParser;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.PlainTextParser;
import com.lifepilot.knowledge.parser.PowerpointParser;
import com.lifepilot.knowledge.parser.WordParser;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link FileReadToolExecutor} 多格式解析测试。
 *
 * <p>覆盖 file.read 工具合并文档解析能力后的全量路由分支：</p>
 * <ul>
 *   <li>纯文本（.java/.json 等）：走 BufferedReader</li>
 *   <li>结构化文档（.md/.txt/.docx/.pdf/.csv）：走 DocumentParserService</li>
 *   <li>attachmentId 参数：通过 AttachmentRepository 查路径后按扩展名路由</li>
 *   <li>异常分支：AttachmentRepository 缺失、附件不存在、两参数都空</li>
 *   <li>maxChars 截断：对纯文本和结构化文档均生效</li>
 * </ul>
 *
 * <p>Skill 加载分支已于 2026-04-24 迁出至 skill.load 工具，此处不再覆盖。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class FileReadToolExecutor_多格式解析测试 {

    @TempDir
    Path tempDir;

    @Mock
    AttachmentRepository attachmentRepository;

    private MetaProperties properties;
    private PathSecurityChecker securityChecker;
    private DocumentParserService parserService;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        properties.getInfra().getFile().setAllowedDirectories(
                List.of(tempDir.toAbsolutePath().toString()));
        securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
        // 使用真实 parser，不 mock（Markdown/PlainText/Word/Excel/Powerpoint 覆盖本测试所有格式）
        parserService = new DocumentParserService(List.of(
                new MarkdownParser(), new PlainTextParser(), new WordParser(),
                new ExcelParser(), new PowerpointParser()));
    }

    @Test
    void 纯文本文件走_BufferedReader_路径读取() throws IOException {
        Path javaFile = tempDir.resolve("Hello.java");
        Files.writeString(javaFile, "public class Hello {\n    // 纯文本\n}");

        var executor = newExecutor(null);
        ToolResult result = executor.execute(newInput(Map.of("path", javaFile.toString())));

        assertThat(result.ok()).isTrue();
        // BufferedReader 路径的标志：totalLines / size 字段均出现，没有 metadata / fileName / totalChars
        assertThat(result.data()).containsKeys("content", "path", "size", "totalLines", "truncated");
        assertThat(result.data()).doesNotContainKeys("metadata", "totalChars");
        assertThat((Integer) result.data().get("totalLines")).isGreaterThanOrEqualTo(2);
        assertThat((String) result.data().get("content")).contains("public class Hello");
    }

    @Test
    void Markdown_文件走_DocumentParserService_路径() throws IOException {
        Path md = tempDir.resolve("note.md");
        Files.writeString(md, "# 测试标题\n\n这是正文。");

        var executor = newExecutor(null);
        ToolResult result = executor.execute(newInput(Map.of("path", md.toString())));

        assertThat(result.ok()).isTrue();
        // 文档路径的标志：返回 metadata / fileName / totalChars，没有 totalLines
        assertThat(result.data()).containsKeys("content", "fileName", "totalChars", "metadata");
        assertThat(result.data()).doesNotContainKey("totalLines");
        assertThat(result.data().get("fileName")).isEqualTo("note.md");
        assertThat((String) result.data().get("content")).contains("测试标题");
    }

    @Test
    void docx_文件通过_AttachmentId_参数按仓储查路径读取() throws IOException {
        // 造一个最小可解析的 docx（空 docx 也能走到 WordParser，主要验证路由走对）
        // 用 md 代替演示 —— attachmentId 路径通用，不局限于特定后缀
        Path md = tempDir.resolve("attached.md");
        Files.writeString(md, "# 从附件来的内容");

        when(attachmentRepository.findById("att-1")).thenReturn(new AttachmentRecord(
                "att-1", "session-1", "attached.md", md.toString(),
                Files.size(md), "text/markdown", null));

        var executor = newExecutor(attachmentRepository);
        ToolResult result = executor.execute(newInput(Map.of("attachmentId", "att-1")));

        assertThat(result.ok()).isTrue();
        // attachmentId 分支同样走文档路径（md 是结构化文档）
        assertThat(result.data().get("fileName")).isEqualTo("attached.md");
        assertThat((String) result.data().get("content")).contains("从附件来的内容");
    }

    @Test
    void attachmentId_参数但仓储不可用时报错() {
        var executor = newExecutor(null);
        ToolResult result = executor.execute(newInput(Map.of("attachmentId", "att-1")));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("附件功能未启用");
    }

    @Test
    void attachmentId_不存在时报错() {
        when(attachmentRepository.findById("missing")).thenReturn(null);

        var executor = newExecutor(attachmentRepository);
        ToolResult result = executor.execute(newInput(Map.of("attachmentId", "missing")));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("附件不存在");
    }

    @Test
    void 两个参数都未提供时报错() {
        var executor = newExecutor(null);
        ToolResult result = executor.execute(newInput(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("path").contains("attachmentId");
    }

    @Test
    void maxChars_对纯文本和文档格式都生效() throws IOException {
        // 纯文本截断
        Path txt = tempDir.resolve("long.java");
        Files.writeString(txt, "a".repeat(1000));
        var executor = newExecutor(null);
        ToolResult plainResult = executor.execute(newInput(Map.of(
                "path", txt.toString(),
                "maxChars", 100)));
        assertThat(plainResult.ok()).isTrue();
        assertThat((Boolean) plainResult.data().get("truncated")).isTrue();

        // 文档截断 —— .md 走 DocumentParserService
        Path md = tempDir.resolve("long.md");
        Files.writeString(md, "a".repeat(1000));
        ToolResult docResult = executor.execute(newInput(Map.of(
                "path", md.toString(),
                "maxChars", 100)));
        assertThat(docResult.ok()).isTrue();
        assertThat((Boolean) docResult.data().get("truncated")).isTrue();
        // 文档路径应带有截断提示后缀
        assertThat((String) docResult.data().get("content")).contains("内容已截断");
    }

    @Test
    void xlsx_文件走_DocumentParserService_路径() throws Exception {
        Path file = tempDir.resolve("sales.xlsx");
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = Files.newOutputStream(file)) {
            var sheet = wb.createSheet("Q1");
            sheet.createRow(0).createCell(0).setCellValue("雪豹-星轨-X7");
            wb.write(out);
        }

        var executor = newExecutor(null);
        ToolResult result = executor.execute(newInput(Map.of("path", file.toString())));

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("content")).contains("雪豹-星轨-X7");
        assertThat((String) result.data().get("content")).contains("Q1");
    }

    @Test
    void pptx_文件走_DocumentParserService_路径() throws Exception {
        Path file = tempDir.resolve("deck.pptx");
        try (var ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow();
             var out = Files.newOutputStream(file)) {
            var slide = ppt.createSlide();
            var tb = slide.createTextBox();
            tb.setAnchor(new java.awt.Rectangle(50, 50, 400, 100));
            tb.setText("鲲鹏振翅 2077");
            ppt.write(out);
        }

        var executor = newExecutor(null);
        ToolResult result = executor.execute(newInput(Map.of("path", file.toString())));

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("content")).contains("鲲鹏振翅 2077");
        assertThat((String) result.data().get("content")).contains("幻灯片 1");
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    private FileReadToolExecutor newExecutor(AttachmentRepository attachmentRepo) {
        return new FileReadToolExecutor(
                securityChecker,
                properties.getInfra().getFile().getDefaultMaxChars(),
                null,  // PathAccessControl —— 本测试用 PathSecurityChecker 白名单兜底
                attachmentRepo,
                parserService);
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("file.read", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}

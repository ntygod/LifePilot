package com.lifepilot.document.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.ReplaceTextOp;
import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.patch.docx.DocxDiffBuilder;
import com.lifepilot.document.patch.docx.DocxPatchEngine;
import com.lifepilot.document.patch.docx.TextAnchorLocator;
import com.lifepilot.document.patch.xlsx.XlsxDiffBuilder;
import com.lifepilot.document.patch.xlsx.XlsxPatchEngine;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.PathSecurityChecker;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DocumentVersionService xlsx 生命周期集成测试 —— checkout / applyPatch 对 xlsx mimeType
 * 的文档全路径工作，同时验证跨 MIME 混用 op 会被 cast 辅助方法拦截。
 *
 * <p>沿用 P3A {@code DocumentVersionService_生命周期测试} 的测试模式：sqlite in-memory +
 * SingleConnectionDataSource + 手动建表，绕开 Spring context 与 Flyway 迁移链。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentVersionService_xlsx生命周期测试 {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/document/sample-sheet.xlsx");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private SessionDocumentRepository documentRepository;
    private DocumentVersionRepository versionRepository;
    private AttachmentRepository attachmentRepository;

    @TempDir
    Path tempDir;

    private DocumentVersionService service;
    private Path sourceCopy;

    @BeforeEach
    void 准备() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");

        // 建表对齐 P3A 生命周期测试 —— session_store / message_attachments /
        // session_documents（含 V12 + V13 扩展列）/ document_versions / V14 唯一索引
        jdbc.execute("CREATE TABLE session_store (session_id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE message_attachments (" +
                "id TEXT PRIMARY KEY, " +
                "entry_id TEXT, " +
                "session_id TEXT NOT NULL, " +
                "file_name TEXT NOT NULL, " +
                "file_path TEXT NOT NULL, " +
                "file_size INTEGER NOT NULL DEFAULT 0, " +
                "mime_type TEXT NOT NULL DEFAULT '', " +
                "url TEXT, " +
                "created_at TEXT NOT NULL, " +
                "FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE session_documents (" +
                "id TEXT PRIMARY KEY, " +
                "session_id TEXT NOT NULL, " +
                "entry_id TEXT, " +
                "file_name TEXT NOT NULL, " +
                "file_path TEXT NOT NULL, " +
                "file_size INTEGER NOT NULL, " +
                "mime_type TEXT NOT NULL, " +
                "origin TEXT NOT NULL, " +
                "source_path TEXT, " +
                "latest_version INTEGER NOT NULL DEFAULT 0, " +
                "created_at TEXT NOT NULL, " +
                "FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE)");
        jdbc.execute("CREATE UNIQUE INDEX uk_session_documents_source_path " +
                "ON session_documents(session_id, source_path) WHERE source_path IS NOT NULL");
        jdbc.execute("CREATE TABLE document_versions (" +
                "id TEXT PRIMARY KEY, " +
                "document_id TEXT NOT NULL, " +
                "version_no INTEGER NOT NULL, " +
                "file_path TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "patch_summary TEXT, " +
                "diff_json TEXT, " +
                "created_at TEXT NOT NULL, " +
                "FOREIGN KEY (document_id) REFERENCES session_documents(id) ON DELETE CASCADE, " +
                "UNIQUE (document_id, version_no))");

        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-xlsx");

        documentRepository = new SessionDocumentRepository(jdbc);
        versionRepository = new DocumentVersionRepository(jdbc);
        attachmentRepository = new AttachmentRepository(jdbc);

        // 把 fixture xlsx 复制到 tempDir 作为 "用户本机路径" 源
        sourceCopy = tempDir.resolve("sheet.xlsx");
        Files.copy(FIXTURE, sourceCopy, StandardCopyOption.REPLACE_EXISTING);

        // 测试用宽松 PathSecurityChecker：无白名单、仅默认黑名单；tempDir 下可自由读写
        service = new DocumentVersionService(
                documentRepository, versionRepository, attachmentRepository,
                new DocxPatchEngine(new TextAnchorLocator()),
                new DocxDiffBuilder(),
                new XlsxPatchEngine(),
                new XlsxDiffBuilder(),
                tempDir.resolve("storage").toString(),
                new PathSecurityChecker(new MetaProperties.Infra.FileAccess()));
    }

    @AfterEach
    void 清理() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("xlsx PathSource checkout 建立 session_documents + v0.xlsx 工作副本")
    void checkout_xlsx路径_写入session_documents并落v0() throws Exception {
        String documentId = service.checkout("sess-xlsx",
                new SourceRef.PathSource(sourceCopy.toString()));

        assertThat(documentId).isNotBlank();
        Path v0 = tempDir.resolve("storage/sess-xlsx/working/" + documentId + "/v0.xlsx");
        assertThat(Files.exists(v0)).isTrue();

        var rec = documentRepository.findById(documentId);
        assertThat(rec).isNotNull();
        assertThat(rec.mimeType()).isEqualTo(DocumentVersionService.XLSX_MIME);
        assertThat(rec.latestVersion()).isZero();
    }

    @Test
    @DisplayName("xlsx applyPatch 成功生成 v1.xlsx 且新版本文件可读")
    void apply_xlsx_patch_成功生成v1且新版本文件可读() throws Exception {
        String documentId = service.checkout("sess-xlsx",
                new SourceRef.PathSource(sourceCopy.toString()));

        var result = service.applyPatch(documentId, List.<DocumentPatchOperation>of(
                new UpdateCellOp("Sheet1", "A2", "活页笔记本", null)));

        assertThat(result.success()).isTrue();
        assertThat(result.newVersion()).isEqualTo(1);

        Path v1 = tempDir.resolve("storage/sess-xlsx/working/" + documentId + "/v1.xlsx");
        assertThat(Files.exists(v1)).isTrue();
        try (InputStream in = Files.newInputStream(v1);
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(0);
            assertThat(c.getStringCellValue()).isEqualTo("活页笔记本");
        }
    }

    @Test
    @DisplayName("xlsx applyPatch diff JSON 包含 mime=xlsx 顶层字段")
    void apply_xlsx_patch_diff_JSON包含mime_xlsx() throws Exception {
        String documentId = service.checkout("sess-xlsx",
                new SourceRef.PathSource(sourceCopy.toString()));

        var r = service.applyPatch(documentId, List.<DocumentPatchOperation>of(
                new UpdateCellOp("Sheet1", "A2", "X", null)));

        assertThat(r.success()).isTrue();
        JsonNode root = new ObjectMapper().readTree(r.diffJson());
        assertThat(root.get("mime").asText()).isEqualTo("xlsx");
    }

    @Test
    @DisplayName("跨 MIME 混用被拒 —— docx op 发往 xlsx 文档抛 IllegalArgumentException")
    void 跨MIME混用被拒_docx_op发往xlsx文档() throws Exception {
        String documentId = service.checkout("sess-xlsx",
                new SourceRef.PathSource(sourceCopy.toString()));

        assertThatThrownBy(() -> service.applyPatch(documentId,
                List.<DocumentPatchOperation>of(
                        new ReplaceTextOp("", "X", "", "Y", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 xlsx 操作");
    }
}

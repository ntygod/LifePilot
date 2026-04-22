package com.lifepilot.document.version;

import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.ReplaceTextOp;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DocumentVersionService 生命周期集成测试 —— 真实 JdbcTemplate + fixture docx。
 *
 * <p>沿用 Repository 层测试模式：sqlite in-memory + SingleConnectionDataSource +
 * 手动建表（对齐 V1 message_attachments / V12 session_documents / V13 document_versions /
 * V14 source_path 唯一约束），绕开 Spring context 与 Flyway 迁移链。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentVersionService_生命周期测试 {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/document/sample-contract.docx");

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

        // session_store 先建（FK 依赖）
        jdbc.execute("CREATE TABLE session_store (session_id TEXT PRIMARY KEY)");
        // message_attachments 对齐 V1__init_schema.sql 中的定义
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
        // session_documents 对齐 V12 + V13（含 P3 扩展列 source_path / latest_version）
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
        // V14：同 session 内 source_path 唯一（只约束 NOT NULL 行）
        jdbc.execute("CREATE UNIQUE INDEX uk_session_documents_source_path " +
                "ON session_documents(session_id, source_path) WHERE source_path IS NOT NULL");
        // document_versions 对齐 V13
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

        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-life");

        documentRepository = new SessionDocumentRepository(jdbc);
        versionRepository = new DocumentVersionRepository(jdbc);
        attachmentRepository = new AttachmentRepository(jdbc);

        // 把 fixture 复制到 tempDir 模拟 "用户本机路径"
        sourceCopy = tempDir.resolve("contract.docx");
        Files.copy(FIXTURE, sourceCopy, StandardCopyOption.REPLACE_EXISTING);

        // 测试场景用宽松的 PathSecurityChecker —— 无白名单仅默认黑名单（/etc、/var、C:\Windows），
        // tempDir 下的路径可自由读写；Critical 拒绝路径用例另行构造受限 checker
        service = new DocumentVersionService(
                new DocumentVersionService.DocumentRepositories(
                        documentRepository, versionRepository, attachmentRepository),
                new DocumentVersionService.PatchEngines(
                        new DocxPatchEngine(new TextAnchorLocator()),
                        new DocxDiffBuilder(),
                        new XlsxPatchEngine(),
                        new XlsxDiffBuilder()),
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
    @DisplayName("PathSource checkout 建立 session_documents + v0 版本")
    void pathSource_checkout建立v0() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));

        var rec = documentRepository.findById(docId);
        assertThat(rec).isNotNull();
        assertThat(rec.sourcePath()).isEqualTo(sourceCopy.toString());
        assertThat(rec.latestVersion()).isZero();
        assertThat(rec.origin()).isEqualTo(
                com.lifepilot.document.model.SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE);
        assertThat(versionRepository.findByDocumentId(docId))
                .extracting(com.lifepilot.document.model.DocumentVersionRecord::versionNo)
                .containsExactly(0);
    }

    @Test
    @DisplayName("applyPatch 成功后 latestVersion++ 且生成 v1 文件")
    void applyPatch成功后版本递增() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        var op = new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期", "付款期限 15 天", null);

        var result = service.applyPatch(docId, List.<DocumentPatchOperation>of(op));

        assertThat(result.success()).isTrue();
        assertThat(result.newVersion()).isEqualTo(1);
        assertThat(result.diffJson()).isNotBlank();
        var rec = documentRepository.findById(docId);
        assertThat(rec.latestVersion()).isEqualTo(1);
        assertThat(Path.of(rec.filePath())).exists();
    }

    @Test
    @DisplayName("applyPatch 失败版本不推进 文件不生成")
    void applyPatch失败不推进版本() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        var badOp = new ReplaceTextOp("", "绝不存在的文字", "", "X", null);

        var result = service.applyPatch(docId, List.<DocumentPatchOperation>of(badOp));

        assertThat(result.success()).isFalse();
        assertThat(documentRepository.findById(docId).latestVersion()).isZero();
    }

    @Test
    @DisplayName("commitOverwrite 覆盖源文件并生成 .bak")
    void commitOverwrite覆盖源文件() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        service.applyPatch(docId, List.<DocumentPatchOperation>of(
                new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期",
                        "付款期限 15 天", null)));

        var result = service.commitOverwrite(docId);

        assertThat(result.committedPath()).isEqualTo(sourceCopy.toString());
        assertThat(Path.of(result.backupPath())).exists();
        // 源文件内容被替换
        try (InputStream in = Files.newInputStream(sourceCopy);
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            assertThat(sb.toString()).contains("付款期限 15 天");
        }
    }

    @Test
    @DisplayName("rollback 产生新版本 内容回到旧版")
    void rollback到旧版本() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        service.applyPatch(docId, List.<DocumentPatchOperation>of(
                new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期",
                        "付款期限 15 天", null)));  // v1

        var result = service.rollback(docId, 0);

        assertThat(result.newVersion()).isEqualTo(2);
        var rec = documentRepository.findById(docId);
        try (InputStream in = Files.newInputStream(Path.of(rec.filePath()));
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            assertThat(sb.toString()).contains("付款期限 30 天");
        }
    }

    @Test
    @DisplayName("Critical 修复 —— checkoutFromPath 拒绝非 .docx 扩展名")
    void checkoutFromPath_拒绝非docx扩展名() throws Exception {
        // 构造一个 .exe 文件（内容无所谓，扩展名先行校验）
        Path fakeExe = tempDir.resolve("bad.exe");
        Files.writeString(fakeExe, "not a docx");

        assertThatThrownBy(() -> service.checkout(
                "sess-life", new SourceRef.PathSource(fakeExe.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持 .docx");
        // 无任何 session_documents / document_versions 落盘
        assertThat(documentRepository.findBySessionAndSourcePath("sess-life", fakeExe.toString()))
                .isNull();
    }

    @Test
    @DisplayName("Critical 修复 —— checkoutFromPath 命中 PathSecurityChecker 黑名单抛 SecurityException")
    void checkoutFromPath_黑名单路径拒绝() throws Exception {
        // 配置一个白名单只包含 tempDir 的 checker，让 tempDir 外的 .docx 也会被拒绝
        var restrictiveConfig = new MetaProperties.Infra.FileAccess();
        restrictiveConfig.setAllowedDirectories(List.of(tempDir.resolve("allowed").toString()));
        var restrictiveChecker = new PathSecurityChecker(restrictiveConfig);
        var restrictedService = new DocumentVersionService(
                new DocumentVersionService.DocumentRepositories(
                        documentRepository, versionRepository, attachmentRepository),
                new DocumentVersionService.PatchEngines(
                        new DocxPatchEngine(new TextAnchorLocator()),
                        new DocxDiffBuilder(),
                        new XlsxPatchEngine(),
                        new XlsxDiffBuilder()),
                tempDir.resolve("storage").toString(),
                restrictiveChecker);

        // sourceCopy 在 tempDir 根目录，不在 allowed 白名单内
        assertThatThrownBy(() -> restrictedService.checkout(
                "sess-life", new SourceRef.PathSource(sourceCopy.toString())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("安全策略");
    }

    @Test
    @DisplayName("Critical 修复 —— commitSaveAs 拒绝命中黑名单的另存路径")
    void commitSaveAs_黑名单路径拒绝() throws Exception {
        // 先用宽松 service 建 checkout，然后用严格 checker 的 service 调 commitSaveAs
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));

        var restrictiveConfig = new MetaProperties.Infra.FileAccess();
        restrictiveConfig.setAllowedDirectories(List.of(tempDir.resolve("allowed").toString()));
        var restrictiveChecker = new PathSecurityChecker(restrictiveConfig);
        var restrictedService = new DocumentVersionService(
                new DocumentVersionService.DocumentRepositories(
                        documentRepository, versionRepository, attachmentRepository),
                new DocumentVersionService.PatchEngines(
                        new DocxPatchEngine(new TextAnchorLocator()),
                        new DocxDiffBuilder(),
                        new XlsxPatchEngine(),
                        new XlsxDiffBuilder()),
                tempDir.resolve("storage").toString(),
                restrictiveChecker);

        Path outside = tempDir.resolve("evil.docx");
        assertThatThrownBy(() -> restrictedService.commitSaveAs(docId, outside.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("安全策略");
    }

    @Test
    @DisplayName("discard 清理工作副本 + 表行")
    void discard清理副本与表行() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        service.applyPatch(docId, List.<DocumentPatchOperation>of(
                new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期",
                        "付款期限 15 天", null)));

        service.discard(docId);

        assertThat(documentRepository.findById(docId)).isNull();
        assertThat(versionRepository.findByDocumentId(docId)).isEmpty();
    }
}

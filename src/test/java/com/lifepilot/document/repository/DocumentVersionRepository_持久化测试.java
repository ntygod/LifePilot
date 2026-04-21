package com.lifepilot.document.repository;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DocumentVersionRepository 持久化测试 —— save / findByDocumentId /
 * findByDocumentIdAndVersion / deleteByDocumentId + UNIQUE(document_id, version_no) 约束。
 *
 * <p>沿用 Phase 2A / Task 1 的测试模式：sqlite in-memory + SingleConnectionDataSource +
 * 手动建表；schema 需与 V12 + V13 + V14 保持同步。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentVersionRepository_持久化测试 {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private SingleConnectionDataSource dataSource;
    private SessionDocumentRepository documentRepository;
    private DocumentVersionRepository versionRepository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void 准备表与文档() {
        // 每个测试独立 in-memory DB（连接常驻 in-memory DB 随连接存活）
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");

        // session_store 先建（FK 依赖）
        jdbc.execute("CREATE TABLE session_store (session_id TEXT PRIMARY KEY)");
        // session_documents 需与 V12 + V13 同步（含 P3 扩展列 source_path / latest_version）
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
        // document_versions 与 V13 同步（UNIQUE(document_id, version_no) 是 Repository 唯一约束用例的依赖）
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

        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-ver");
        documentRepository = new SessionDocumentRepository(jdbc);
        versionRepository = new DocumentVersionRepository(jdbc);
        documentRepository.save(new SessionDocumentRecord(
                "doc-ver", "sess-ver", null, "x.docx", "/p/x", 1L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, "D:/x.docx", 0, Instant.now()));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("保存 initial 版本并按 documentId 读回")
    void 保存initial版本并按documentId读回() {
        versionRepository.save(new DocumentVersionRecord(
                "v-1", "doc-ver", 0, "/w/v0.docx", DocumentVersionRecord.SOURCE_INITIAL,
                null, null, Instant.now()));

        var list = versionRepository.findByDocumentId("doc-ver");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).versionNo()).isZero();
        assertThat(list.get(0).source()).isEqualTo(DocumentVersionRecord.SOURCE_INITIAL);
    }

    @Test
    @DisplayName("多版本按 versionNo 升序返回")
    void 多版本按versionNo升序返回() {
        versionRepository.save(new DocumentVersionRecord(
                "v-a", "doc-ver", 2, "/w/v2.docx", DocumentVersionRecord.SOURCE_PATCH,
                "共 2 处", "{}", Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                "v-b", "doc-ver", 0, "/w/v0.docx", DocumentVersionRecord.SOURCE_INITIAL,
                null, null, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                "v-c", "doc-ver", 1, "/w/v1.docx", DocumentVersionRecord.SOURCE_PATCH,
                "共 1 处", "{}", Instant.now()));

        var list = versionRepository.findByDocumentId("doc-ver");

        assertThat(list).extracting(DocumentVersionRecord::versionNo).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("findByDocumentIdAndVersion 命中")
    void 按版本号精确查找() {
        versionRepository.save(new DocumentVersionRecord(
                "v-x", "doc-ver", 5, "/w/v5.docx", DocumentVersionRecord.SOURCE_ROLLBACK,
                "回滚到 v2", null, Instant.now()));

        var found = versionRepository.findByDocumentIdAndVersion("doc-ver", 5);

        assertThat(found).isNotNull();
        assertThat(found.source()).isEqualTo(DocumentVersionRecord.SOURCE_ROLLBACK);
    }

    @Test
    @DisplayName("deleteByDocumentId 级联删除所有版本")
    void deleteByDocumentId级联删除() {
        versionRepository.save(new DocumentVersionRecord(
                "v-d1", "doc-ver", 0, "/w/v0.docx", DocumentVersionRecord.SOURCE_INITIAL,
                null, null, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                "v-d2", "doc-ver", 1, "/w/v1.docx", DocumentVersionRecord.SOURCE_PATCH,
                "s", null, Instant.now()));

        int affected = versionRepository.deleteByDocumentId("doc-ver");

        assertThat(affected).isEqualTo(2);
        assertThat(versionRepository.findByDocumentId("doc-ver")).isEmpty();
    }

    @Test
    @DisplayName("UNIQUE(document_id, version_no) 约束生效")
    void 重复版本号触发唯一约束() {
        versionRepository.save(new DocumentVersionRecord(
                "v-u1", "doc-ver", 1, "/w/v1.docx", DocumentVersionRecord.SOURCE_PATCH,
                null, null, Instant.now()));

        var duplicate = new DocumentVersionRecord(
                "v-u2", "doc-ver", 1, "/w/v1b.docx", DocumentVersionRecord.SOURCE_PATCH,
                null, null, Instant.now());

        // SQLite 方言不在 Spring 默认 SQLErrorCodes 表里，
        // UNIQUE 冲突会被包装成 DataAccessException 子类（而非具体的 DuplicateKeyException），
        // 断言到父类层面足以证明 UNIQUE(document_id, version_no) 约束命中
        assertThatThrownBy(() -> versionRepository.save(duplicate))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE");
    }
}

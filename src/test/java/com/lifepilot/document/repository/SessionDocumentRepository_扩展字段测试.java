package com.lifepilot.document.repository;

import com.lifepilot.document.model.SessionDocumentRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionDocumentRepository P3 扩展字段测试 —— 覆盖 sourcePath / latestVersion
 * 读写 + updateLatestVersion / updateFilePath / findBySessionAndSourcePath / deleteById。
 *
 * <p>承接 Phase 2A 既有 {@link SessionDocumentRepository_持久化测试} 的测试模式：
 * sqlite in-memory + SingleConnectionDataSource + 手动建表，绕开 Spring context
 * 与 Flyway 迁移链，让测试聚焦 Repository 自身行为。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
class SessionDocumentRepository_扩展字段测试 {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private SingleConnectionDataSource dataSource;
    private SessionDocumentRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void 准备表与会话() {
        // 每个测试独立 in-memory DB（SingleConnectionDataSource 连接常驻，in-memory DB 随连接存活）
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");

        // 建表 schema 需与 V12 + V13 同步 —— 含 P3 新增 source_path / latest_version 列
        jdbc.execute("CREATE TABLE session_store (session_id TEXT PRIMARY KEY)");
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
        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-p3");

        repository = new SessionDocumentRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("保存并按 sessionId+sourcePath 读回")
    void 保存并按sessionId与sourcePath读回() {
        var record = new SessionDocumentRecord(
                "doc-1", "sess-p3", null, "甲方合同.docx",
                "/tmp/doc.docx", 123L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE,
                "D:/合同/甲方.docx", 0, Instant.now());
        repository.save(record);

        var found = repository.findBySessionAndSourcePath("sess-p3", "D:/合同/甲方.docx");

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("doc-1");
        assertThat(found.latestVersion()).isZero();
        assertThat(found.sourcePath()).isEqualTo("D:/合同/甲方.docx");
    }

    @Test
    @DisplayName("updateLatestVersion 递增版本号")
    void updateLatestVersion递增版本号() {
        var record = new SessionDocumentRecord(
                "doc-2", "sess-p3", null, "x.docx", "/tmp/x.docx", 1L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, "D:/x.docx", 0, Instant.now());
        repository.save(record);

        int affected = repository.updateLatestVersion("doc-2", 3);

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById("doc-2").latestVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("updateFilePath 同步更新 filePath 与 fileSize")
    void updateFilePath同步更新路径与大小() {
        var record = new SessionDocumentRecord(
                "doc-3", "sess-p3", null, "y.docx", "/old/path.docx", 100L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now());
        repository.save(record);

        repository.updateFilePath("doc-3", "/new/path/v1.docx", 250L);

        var found = repository.findById("doc-3");
        assertThat(found.filePath()).isEqualTo("/new/path/v1.docx");
        assertThat(found.fileSize()).isEqualTo(250L);
    }

    @Test
    @DisplayName("找不到 sourcePath 返回 null")
    void 找不到sourcePath返回null() {
        assertThat(repository.findBySessionAndSourcePath("sess-p3", "不存在.docx")).isNull();
    }

    @Test
    @DisplayName("deleteById 删除并留其他记录")
    void deleteById删除指定记录() {
        var a = new SessionDocumentRecord("doc-a", "sess-p3", null, "a.docx", "/p/a", 1L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now());
        var b = new SessionDocumentRecord("doc-b", "sess-p3", null, "b.docx", "/p/b", 1L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now());
        repository.save(a);
        repository.save(b);

        int affected = repository.deleteById("doc-a");

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById("doc-a")).isNull();
        assertThat(repository.findById("doc-b")).isNotNull();
    }
}

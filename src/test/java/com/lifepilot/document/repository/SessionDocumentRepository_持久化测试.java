package com.lifepilot.document.repository;

import com.lifepilot.document.model.SessionDocumentRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionDocumentRepository 持久化测试。
 *
 * <p>使用 sqlite in-memory + SingleConnectionDataSource 对齐项目既有 JDBC 集成测试模式
 * （见 QueuedActionRepository_集成测试）。schema 手动建表，不走 Flyway，避免拖入完整迁移链。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
class SessionDocumentRepository_持久化测试 {

    private SingleConnectionDataSource dataSource;
    private SessionDocumentRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // 每个测试独立 in-memory DB（SingleConnectionDataSource 连接常驻，in-memory DB 随连接存活）
        dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);

        // SQLite 默认 FK 校验关闭，手动开启以验证 CASCADE 语义（生产由 Flyway + runtime 启用）
        jdbc.execute("PRAGMA foreign_keys = ON");

        // session_store 需要先建（外键依赖）
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
                "created_at TEXT NOT NULL, " +
                "FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE)");
        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-1");

        repository = new SessionDocumentRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void save_返回持久化的_id() {
        var rec = new SessionDocumentRecord(null, "sess-1", null,
                "Q3 报表.docx", "/tmp/q3.docx", 1024L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now());

        String id = repository.save(rec);

        assertThat(id).isNotBlank();
    }

    @Test
    void findById_能取回已保存记录() {
        var rec = new SessionDocumentRecord("doc-1", "sess-1", "entry-9",
                "报告.docx", "/tmp/r.docx", 2048L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, Instant.parse("2026-04-20T10:00:00Z"));
        repository.save(rec);

        var found = repository.findById("doc-1");

        assertThat(found).isNotNull();
        assertThat(found.fileName()).isEqualTo("报告.docx");
        assertThat(found.origin()).isEqualTo("agent_generated");
        assertThat(found.entryId()).isEqualTo("entry-9");
    }

    @Test
    void findById_未找到返回_null() {
        assertThat(repository.findById("missing")).isNull();
    }

    @Test
    void findBySessionId_按创建时间倒序() {
        var r1 = new SessionDocumentRecord("a", "sess-1", null, "a.docx", "/tmp/a", 1L, "x", "agent_generated",
                Instant.parse("2026-04-20T10:00:00Z"));
        var r2 = new SessionDocumentRecord("b", "sess-1", null, "b.docx", "/tmp/b", 1L, "x", "agent_generated",
                Instant.parse("2026-04-20T11:00:00Z"));
        repository.save(r1);
        repository.save(r2);

        var list = repository.findBySessionId("sess-1");

        assertThat(list).hasSize(2);
        assertThat(list.get(0).id()).isEqualTo("b");  // 最新在前
        assertThat(list.get(1).id()).isEqualTo("a");
    }

    @Test
    void deleteBySessionId_级联清理() {
        repository.save(new SessionDocumentRecord("x", "sess-1", null, "x.docx", "/t/x", 1L, "x",
                "agent_generated", Instant.now()));

        int deleted = repository.deleteBySessionId("sess-1");

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findBySessionId("sess-1")).isEmpty();
    }

    @Test
    void 删除_session_store_时_FK_级联清理_documents() {
        repository.save(new SessionDocumentRecord("c", "sess-1", null, "c.docx", "/t/c", 1L, "x",
                "agent_generated", Instant.now()));
        assertThat(repository.findBySessionId("sess-1")).hasSize(1);

        jdbc.update("DELETE FROM session_store WHERE session_id = ?", "sess-1");

        // FK ON DELETE CASCADE 应自动清 session_documents 表的关联行
        assertThat(repository.findBySessionId("sess-1")).isEmpty();
    }
}

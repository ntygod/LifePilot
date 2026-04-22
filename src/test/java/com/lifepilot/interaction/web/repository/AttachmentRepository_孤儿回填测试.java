package com.lifepilot.interaction.web.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentRepository_孤儿回填测试 {

    private SingleConnectionDataSource dataSource;
    private AttachmentRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");

        jdbc.execute("CREATE TABLE session_store (session_id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE message_attachments (" +
                "id TEXT PRIMARY KEY, entry_id TEXT, session_id TEXT NOT NULL, " +
                "file_name TEXT, file_path TEXT, file_size INTEGER, mime_type TEXT, " +
                "url TEXT, created_at TEXT)");
        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-1");
        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-2");
        repository = new AttachmentRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void backfillOrphanEntryIds_把_session_内_entry_id_null_的记录填上新_entry_id() {
        repository.saveForEntry(null, "sess-1", "a.docx", "/t/a", 1L, "x", "/u/a");
        repository.saveForEntry(null, "sess-1", "b.docx", "/t/b", 2L, "x", "/u/b");

        int updated = repository.backfillOrphanEntryIds("sess-1", "entry-new");

        assertThat(updated).isEqualTo(2);
        Integer remainingNull = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_attachments WHERE session_id = ? AND entry_id IS NULL",
                Integer.class, "sess-1");
        assertThat(remainingNull).isZero();
    }

    @Test
    void backfillOrphanEntryIds_不影响其他_session() {
        repository.saveForEntry(null, "sess-1", "a.docx", "/t/a", 1L, "x", "/u/a");
        repository.saveForEntry(null, "sess-2", "b.docx", "/t/b", 1L, "x", "/u/b");

        repository.backfillOrphanEntryIds("sess-1", "entry-new");

        Integer sess2Null = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_attachments WHERE session_id = ? AND entry_id IS NULL",
                Integer.class, "sess-2");
        assertThat(sess2Null).isEqualTo(1);  // sess-2 的 orphan 不被回填
    }

    @Test
    void backfillOrphanEntryIds_不覆盖已有_entry_id() {
        repository.saveForEntry("entry-existing", "sess-1", "a.docx", "/t/a", 1L, "x", "/u/a");
        repository.saveForEntry(null, "sess-1", "b.docx", "/t/b", 2L, "x", "/u/b");

        repository.backfillOrphanEntryIds("sess-1", "entry-new");

        // 只有 orphan 被填，不动已有 entry_id
        Integer underExisting = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_attachments WHERE entry_id = ?",
                Integer.class, "entry-existing");
        Integer underNew = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_attachments WHERE entry_id = ?",
                Integer.class, "entry-new");
        assertThat(underExisting).isEqualTo(1);
        assertThat(underNew).isEqualTo(1);
    }

    // ---------- P3 扩展：updateSizeByFilePath ----------

    @Test
    void 按文件路径更新尺寸() {
        // 工作副本 commit / rollback 后需要刷新 message_attachments 显示的文件大小
        repository.saveForEntry(null, "sess-1", "a.docx", "/p/working/a.docx",
                100L, "application/docx", "/api/documents/x/download");

        int affected = repository.updateSizeByFilePath("/p/working/a.docx", 250L);

        assertThat(affected).isEqualTo(1);
        Long newSize = jdbc.queryForObject(
                "SELECT file_size FROM message_attachments WHERE file_path = ?",
                Long.class, "/p/working/a.docx");
        assertThat(newSize).isEqualTo(250L);
    }

    @Test
    void 路径不存在返回零() {
        // 文档尚未挂到消息气泡时（路径无匹配行）不应视为错误，返回 0 即可
        int affected = repository.updateSizeByFilePath("/不存在.docx", 100L);

        assertThat(affected).isZero();
    }
}

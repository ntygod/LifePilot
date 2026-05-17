package com.lifepilot.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 下架文档工作区迁移集成测试。
 *
 * <p>覆盖两类场景：</p>
 * <ul>
 *   <li>干净 SQLite 跑 V1 + V2 后，{@code session_documents} / {@code document_versions} 两表均不存在。</li>
 *   <li>跑完 V1 后插入若干 {@code session_documents} + {@code document_versions} 行，再跑 V2 → 两表消失，无报错。</li>
 * </ul>
 *
 * <p>实施方式：Flyway 直接在文件 SQLite 上跑 {@code classpath:db/migration} 下全部脚本，
 * 不启动 Spring 上下文，避免拉起完整 Bean 图。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class MigrationV2_drop_document_workspace_集成测试 {

    private static String jdbcUrlOf(Path dbPath) {
        return "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");
    }

    @Test
    @DisplayName("干净 SQLite 跑 V1 + V2 后两表均不存在")
    void 干净库跑V2后两表消失(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("v2-clean.db");
        String jdbcUrl = jdbcUrlOf(dbPath);

        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            assertThat(tableExists(conn, "session_documents")).isFalse();
            assertThat(tableExists(conn, "document_versions")).isFalse();
        }
    }

    @Test
    @DisplayName("V1 后插入数据再跑 V2 仍可成功删除两表")
    void 已含数据跑V2不报错(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("v2-seeded.db");
        String jdbcUrl = jdbcUrlOf(dbPath);

        // 仅跑到 V1（target 限定为 1）
        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate();

        // 插入一行 session_documents + 一行 document_versions（先关闭外键避免 session_store 依赖）
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            assertThat(tableExists(conn, "session_documents")).isTrue();
            assertThat(tableExists(conn, "document_versions")).isTrue();

            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = OFF");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO session_documents (id, session_id, entry_id, file_name, file_path, "
                            + "file_size, mime_type, origin, source_path, latest_version, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, "doc-1");
                ps.setString(2, "session-1");
                ps.setString(3, "entry-1");
                ps.setString(4, "source.docx");
                ps.setString(5, "/tmp/source.docx");
                ps.setLong(6, 1024L);
                ps.setString(7, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                ps.setString(8, "USER_LOCAL_FILE");
                ps.setString(9, "/tmp/source.docx");
                ps.setInt(10, 0);
                ps.setString(11, "2026-05-17T00:00:00Z");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO document_versions (id, document_id, version_no, file_path, source, "
                            + "patch_summary, diff_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, "ver-1");
                ps.setString(2, "doc-1");
                ps.setInt(3, 0);
                ps.setString(4, "/tmp/v0.docx");
                ps.setString(5, "USER_LOCAL_FILE");
                ps.setString(6, null);
                ps.setString(7, null);
                ps.setString(8, "2026-05-17T00:00:00Z");
                ps.executeUpdate();
            }
        }

        // 跑 V2，期望不报错
        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            assertThat(tableExists(conn, "session_documents")).isFalse();
            assertThat(tableExists(conn, "document_versions")).isFalse();
        }
    }

    private static boolean tableExists(Connection conn, String tableName) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
            return rs.next();
        }
    }
}

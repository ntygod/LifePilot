package com.lifepilot.skill.marketplace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway V35 迁移脚本集成测试。
 *
 * <p>手动执行 V35 DDL，验证 installed_skills 和 marketplace_index_cache 表正确创建，
 * 包括列结构、UNIQUE 约束和基本 CRUD 操作。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class FlywayV35_集成测试 {

    private JdbcTemplate jdbcTemplate;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        executeMigration();
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void installed_skills表_创建成功_可插入查询() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO installed_skills
                    (id, package_id, name, version, index_source_url, repo_url,
                     file_path, security_report_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, "pkg-todo", "Todo 助手", "1.0.0",
                "https://example.com/index.json",
                "https://github.com/test/repo",
                "skills/todo.yaml",
                "{\"overallRisk\":\"LOW\"}",
                now.toString(), now.toString());

        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM installed_skills", Integer.class);
        assertThat(count).isEqualTo(1);

        // 验证列值可正确读取
        var row = jdbcTemplate.queryForMap(
                "SELECT * FROM installed_skills WHERE id = ?", id);
        assertThat(row.get("package_id")).isEqualTo("pkg-todo");
        assertThat(row.get("name")).isEqualTo("Todo 助手");
        assertThat(row.get("version")).isEqualTo("1.0.0");
        assertThat(row.get("index_source_url")).isEqualTo("https://example.com/index.json");
        assertThat(row.get("repo_url")).isEqualTo("https://github.com/test/repo");
        assertThat(row.get("file_path")).isEqualTo("skills/todo.yaml");
        assertThat(row.get("security_report_json")).isEqualTo("{\"overallRisk\":\"LOW\"}");
    }

    @Test
    void installed_skills表_security_report_json可为null() {
        assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO installed_skills
                    (id, package_id, name, version, index_source_url, repo_url,
                     file_path, security_report_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """,
                UUID.randomUUID().toString(), "pkg-null", "测试",
                "1.0.0", "https://example.com/index.json",
                "https://github.com/test/repo", "skills/test.yaml",
                Instant.now().toString(), Instant.now().toString())
        ).doesNotThrowAnyException();
    }

    @Test
    void installed_skills表_package_id唯一约束_重复插入抛异常() {
        Instant now = Instant.now();
        String packageId = "pkg-duplicate";

        // 第一次插入成功
        jdbcTemplate.update("""
                INSERT INTO installed_skills
                    (id, package_id, name, version, index_source_url, repo_url,
                     file_path, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), packageId, "Skill A", "1.0.0",
                "https://example.com/index.json",
                "https://github.com/test/repo", "skills/a.yaml",
                now.toString(), now.toString());

        // 第二次插入相同 package_id 应抛异常
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO installed_skills
                    (id, package_id, name, version, index_source_url, repo_url,
                     file_path, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), packageId, "Skill B", "2.0.0",
                "https://example.com/index.json",
                "https://github.com/test/repo", "skills/b.yaml",
                now.toString(), now.toString())
        ).hasMessageContaining("UNIQUE constraint failed");
    }

    @Test
    void marketplace_index_cache表_创建成功_可插入查询() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO marketplace_index_cache
                    (id, source_url, index_json, fetched_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, "https://example.com/index.json",
                "[{\"id\":\"pkg-1\",\"name\":\"Test\"}]",
                now.toString(), now.toString());

        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketplace_index_cache", Integer.class);
        assertThat(count).isEqualTo(1);

        var row = jdbcTemplate.queryForMap(
                "SELECT * FROM marketplace_index_cache WHERE id = ?", id);
        assertThat(row.get("source_url")).isEqualTo("https://example.com/index.json");
        assertThat(row.get("index_json")).isEqualTo("[{\"id\":\"pkg-1\",\"name\":\"Test\"}]");
    }

    @Test
    void marketplace_index_cache表_source_url唯一约束_重复插入抛异常() {
        Instant now = Instant.now();
        String sourceUrl = "https://example.com/index.json";

        // 第一次插入成功
        jdbcTemplate.update("""
                INSERT INTO marketplace_index_cache
                    (id, source_url, index_json, fetched_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), sourceUrl, "[]",
                now.toString(), now.toString());

        // 第二次插入相同 source_url 应抛异常
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO marketplace_index_cache
                    (id, source_url, index_json, fetched_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), sourceUrl, "[{\"id\":\"new\"}]",
                now.toString(), now.toString())
        ).hasMessageContaining("UNIQUE constraint failed");
    }

    /** 执行 V35 迁移脚本中的 DDL。 */
    private void executeMigration() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS installed_skills (
                    id                  TEXT PRIMARY KEY,
                    package_id          TEXT NOT NULL UNIQUE,
                    name                TEXT NOT NULL,
                    version             TEXT NOT NULL,
                    index_source_url    TEXT NOT NULL,
                    repo_url            TEXT NOT NULL,
                    file_path           TEXT NOT NULL,
                    security_report_json TEXT,
                    created_at          TEXT NOT NULL,
                    updated_at          TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS marketplace_index_cache (
                    id          TEXT PRIMARY KEY,
                    source_url  TEXT NOT NULL UNIQUE,
                    index_json  TEXT NOT NULL,
                    fetched_at  TEXT NOT NULL,
                    created_at  TEXT NOT NULL
                )
                """);
    }
}

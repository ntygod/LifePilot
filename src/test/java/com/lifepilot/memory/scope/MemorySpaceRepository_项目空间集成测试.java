package com.lifepilot.memory.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.store.scope.MemorySpace;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.scope.MemorySpaceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MemorySpaceRepository 项目空间功能集成测试 —— 验证 PROJECT 类型记忆空间的
 * 创建、幂等获取、按 id 删除等能力。
 *
 * <p>使用内存 SQLite + 手动建表，与项目现有集成测试（如 CronTaskRepository_集成测试）
 * 保持一致，避免 @SpringBootTest 的完整上下文加载开销。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class MemorySpaceRepository_项目空间集成测试 {

    private MemorySpaceRepository repository;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
        // 与 V1__init_schema.sql 中 memory_spaces 表结构保持一致
        jdbcTemplate.execute("""
                CREATE TABLE memory_spaces (
                    id            TEXT PRIMARY KEY,
                    space_key     TEXT NOT NULL UNIQUE,
                    space_type    TEXT NOT NULL,
                    display_name  TEXT NOT NULL,
                    owner_type    TEXT,
                    owner_id      TEXT,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    created_at    TEXT NOT NULL,
                    updated_at    TEXT NOT NULL
                )""");
        repository = new MemorySpaceRepository(jdbcTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void ensureProjectSpace_首次创建_type为PROJECT() {
        MemorySpace space = repository.ensureProjectSpace("project-test-123");

        assertThat(space.spaceType()).isEqualTo(MemorySpaceType.PROJECT);
        assertThat(space.spaceKey()).isEqualTo("project:project-test-123");
        assertThat(space.ownerType()).isEqualTo("PROJECT");
        assertThat(space.ownerId()).isEqualTo("project-test-123");
        assertThat(space.metadata()).containsEntry("projectId", "project-test-123");
    }

    @Test
    void ensureProjectSpace_重复调用_返回同一个空间() {
        MemorySpace a = repository.ensureProjectSpace("project-test-456");
        MemorySpace b = repository.ensureProjectSpace("project-test-456");

        assertThat(b.id()).isEqualTo(a.id());
        assertThat(b.spaceKey()).isEqualTo(a.spaceKey());
    }

    @Test
    void deleteById_成功后_findById返回空() {
        MemorySpace created = repository.ensureProjectSpace("project-test-delete");

        repository.deleteById(created.id());

        assertThat(repository.findById(created.id())).isEmpty();
    }

    @Test
    void metadataJson被污染时读取应失败() {
        MemorySpace created = repository.ensureProjectSpace("project-test-broken-metadata");
        jdbcTemplate.update("UPDATE memory_spaces SET metadata_json = ? WHERE id = ?",
                "{不是合法JSON", created.id());

        assertThatThrownBy(() -> repository.findById(created.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadata_json")
                .hasMessageContaining(created.id());
    }
}

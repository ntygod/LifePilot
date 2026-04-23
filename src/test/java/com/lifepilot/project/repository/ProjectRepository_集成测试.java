package com.lifepilot.project.repository;

import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProjectRepository 集成测试 —— 使用 SQLite 内存库 + 手动建表验证 CRUD。
 *
 * <p>遵循项目现有集成测试模式（参照 MemorySpaceRepository_项目空间集成测试），
 * 避免 @SpringBootTest 的完整上下文加载开销。测试中 projects 表省略 FK 约束，
 * FK 行为在其他层覆盖，本测试只负责验证 Repository 的 CRUD 契约。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ProjectRepository_集成测试 {

    private ProjectRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 对照 V15__create_projects_table.sql 的结构，测试级简化：去掉 FK 约束
        jdbc.execute("""
                CREATE TABLE projects (
                    id              TEXT PRIMARY KEY,
                    name            TEXT NOT NULL,
                    instructions    TEXT NOT NULL DEFAULT '',
                    isolation       TEXT NOT NULL DEFAULT 'ISOLATED'
                                    CHECK (isolation IN ('ISOLATED', 'SHARED')),
                    memory_space_id TEXT NOT NULL,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL,
                    UNIQUE(name)
                )""");
        repository = new ProjectRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void insert_后_findById_能找到() {
        Instant now = Instant.now();
        Project p = new Project(
                UUID.randomUUID().toString(),
                "论文",
                "严谨学术风",
                ProjectIsolation.ISOLATED,
                "space-1",
                now,
                now);

        repository.insert(p);

        Project found = repository.findById(p.id()).orElseThrow();
        assertThat(found.name()).isEqualTo(p.name());
        assertThat(found.instructions()).isEqualTo(p.instructions());
        assertThat(found.isolation()).isEqualTo(p.isolation());
        assertThat(found.memorySpaceId()).isEqualTo(p.memorySpaceId());
    }

    @Test
    void findAll_按创建时间降序() {
        Instant earlier = Instant.now().minus(Duration.ofMinutes(10));
        Instant later = Instant.now();
        repository.insert(new Project("a", "旧项目", "", ProjectIsolation.ISOLATED, "s1", earlier, earlier));
        repository.insert(new Project("b", "新项目", "", ProjectIsolation.ISOLATED, "s2", later, later));

        List<Project> list = repository.findAll();

        assertThat(list).hasSize(2);
        // 更晚创建的项目在前
        assertThat(list.get(0).id()).isEqualTo("b");
        assertThat(list.get(1).id()).isEqualTo("a");
    }

    @Test
    void update_可改名_可改指示词_可改isolation() {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        repository.insert(new Project(id, "原名", "", ProjectIsolation.ISOLATED, "s1", now, now));

        Instant later = now.plusSeconds(1);
        repository.update(new Project(id, "新名", "新指示词", ProjectIsolation.SHARED, "s1", now, later));

        Project updated = repository.findById(id).orElseThrow();
        assertThat(updated.name()).isEqualTo("新名");
        assertThat(updated.instructions()).isEqualTo("新指示词");
        assertThat(updated.isolation()).isEqualTo(ProjectIsolation.SHARED);
        assertThat(updated.updatedAt()).isEqualTo(later);
    }

    @Test
    void existsByName_判断重名() {
        Instant now = Instant.now();
        repository.insert(new Project(
                UUID.randomUUID().toString(),
                "论文",
                "",
                ProjectIsolation.ISOLATED,
                "s1",
                now,
                now));

        assertThat(repository.existsByName("论文")).isTrue();
        assertThat(repository.existsByName("小说")).isFalse();
    }

    @Test
    void deleteById_后_找不到() {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        repository.insert(new Project(id, "待删", "", ProjectIsolation.ISOLATED, "s1", now, now));

        repository.deleteById(id);

        assertThat(repository.findById(id)).isEmpty();
    }
}

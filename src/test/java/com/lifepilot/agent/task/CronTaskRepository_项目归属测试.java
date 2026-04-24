package com.lifepilot.agent.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CronTaskRepository 项目归属字段集成测试（Plan 2 Task A2）。
 *
 * <p>验证 {@code project_id} 字段在读写路径上的完整语义：</p>
 * <ul>
 *   <li>save 写入 projectId 后 findById 能回读</li>
 *   <li>findByProjectId 只返回归属该项目的任务</li>
 *   <li>findByProjectId(null) 返回归属主账户（project_id IS NULL）的任务</li>
 *   <li>update 不应覆盖 project_id（项目归属不可迁移）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
class CronTaskRepository_项目归属测试 {

    private CronTaskRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");
        // 对照 V1 + V3 + V19 叠加后的最终 schema（project_id 来自 V19）
        jdbc.execute("""
                CREATE TABLE cron_tasks (
                    id          TEXT PRIMARY KEY,
                    name        TEXT NOT NULL,
                    schedule    TEXT NOT NULL,
                    instruction TEXT NOT NULL,
                    status      TEXT NOT NULL DEFAULT 'active',
                    created_at  TEXT NOT NULL,
                    updated_at  TEXT NOT NULL,
                    skill_ids   TEXT,
                    project_id  TEXT
                )""");
        repository = new CronTaskRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private CronTaskEntry 建任务(String id, String projectId) {
        var now = Instant.now().toString();
        return new CronTaskEntry(
                id, "任务-" + id, "0 0 21 ? * SUN", "执行指令",
                "active", now, now, null, projectId);
    }

    @Test
    void save_携带projectId_能回读() {
        var id = UUID.randomUUID().toString();
        repository.save(建任务(id, "p-1"));

        var found = repository.findById(id).orElseThrow();
        assertThat(found.projectId()).isEqualTo("p-1");
    }

    @Test
    void save_projectId为null_回读也为null() {
        var id = UUID.randomUUID().toString();
        repository.save(建任务(id, null));

        assertThat(repository.findById(id).orElseThrow().projectId()).isNull();
    }

    @Test
    void findByProjectId_只返回归属该项目() {
        repository.save(建任务("t1", "p-1"));
        repository.save(建任务("t2", "p-1"));
        repository.save(建任务("t3", null));
        repository.save(建任务("t4", "p-2"));

        var p1 = repository.findByProjectId("p-1");
        assertThat(p1).hasSize(2);
        assertThat(p1).allMatch(e -> "p-1".equals(e.projectId()));
    }

    @Test
    void findByProjectId_传null_返回主账户任务() {
        repository.save(建任务("t1", "p-1"));
        repository.save(建任务("t2", null));
        repository.save(建任务("t3", null));

        var main = repository.findByProjectId(null);
        assertThat(main).hasSize(2);
        assertThat(main).allMatch(e -> e.projectId() == null);
    }

    @Test
    void update_不改project_id() {
        var id = UUID.randomUUID().toString();
        repository.save(建任务(id, "p-1"));

        var original = repository.findById(id).orElseThrow();
        var later = Instant.now().plusSeconds(1).toString();
        // update 只改 name（模拟真实 update 路径），验证 project_id 保留
        repository.update(new CronTaskEntry(
                id, "新名", original.schedule(), original.instruction(),
                original.status(), original.createdAt(), later,
                original.skillIds(), "p-2"));

        // 即便传入不同 projectId，也不应被写入（归属不可迁移）
        var after = repository.findById(id).orElseThrow();
        assertThat(after.name()).isEqualTo("新名");
        assertThat(after.projectId()).isEqualTo("p-1");
    }
}

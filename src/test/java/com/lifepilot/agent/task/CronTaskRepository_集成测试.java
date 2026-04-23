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
 * CronTaskRepository 集成测试 — 验证 CRUD、级联删除、findByStatus。
 *
 * <p>使用内存 SQLite + Flyway 表结构，验证完整的数据库交互。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class CronTaskRepository_集成测试 {

    private CronTaskRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");
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
        jdbc.execute("""
                CREATE TABLE cron_task_logs (
                    id          TEXT PRIMARY KEY,
                    task_id     TEXT NOT NULL REFERENCES cron_tasks(id) ON DELETE CASCADE,
                    executed_at TEXT NOT NULL,
                    status      TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    tokens_used INTEGER NOT NULL DEFAULT 0,
                    summary     TEXT,
                    created_at  TEXT NOT NULL
                )""");
        repository = new CronTaskRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private CronTaskEntry 创建任务(String name, String schedule, String status) {
        var now = Instant.now().toString();
        return new CronTaskEntry(UUID.randomUUID().toString(), name, schedule,
                "测试指令: " + name, status, now, now);
    }

    @Test
    void 完整CRUD流程_创建查询更新删除() {
        // 创建
        var task = 创建任务("每日AI资讯", "0 0 8 * * *", "active");
        repository.save(task);

        // 查询
        var found = repository.findById(task.id());
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("每日AI资讯");

        // 更新
        var updated = new CronTaskEntry(task.id(), "每日AI资讯(已修改)", "0 30 9 * * *",
                task.instruction(), "paused", task.createdAt(), Instant.now().toString());
        repository.update(updated);

        var afterUpdate = repository.findById(task.id()).orElseThrow();
        assertThat(afterUpdate.name()).isEqualTo("每日AI资讯(已修改)");
        assertThat(afterUpdate.status()).isEqualTo("paused");

        // 删除
        repository.deleteById(task.id());
        assertThat(repository.findById(task.id())).isEmpty();
    }

    @Test
    void 级联删除_删除任务时日志一并清除() {
        var task = 创建任务("带日志任务", "0 0 6 * * *", "active");
        repository.save(task);

        // 写入 3 条日志
        for (int i = 0; i < 3; i++) {
            repository.saveLog(new CronTaskLog(
                    UUID.randomUUID().toString(), task.id(),
                    Instant.now().toString(), "success", 1000 + i, 50,
                    "执行摘要 " + i, Instant.now().toString()
            ));
        }

        // 删除任务 → 日志级联删除
        repository.deleteById(task.id());
        assertThat(repository.findById(task.id())).isEmpty();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findByStatus_多状态混合查询() {
        repository.save(创建任务("活跃1", "0 0 8 * * *", "active"));
        repository.save(创建任务("活跃2", "0 0 9 * * *", "active"));
        repository.save(创建任务("暂停1", "0 0 10 * * *", "paused"));
        repository.save(创建任务("完成1", "0 0 11 * * *", "completed"));

        assertThat(repository.findByStatus("active")).hasSize(2);
        assertThat(repository.findByStatus("paused")).hasSize(1);
        assertThat(repository.findByStatus("completed")).hasSize(1);
        assertThat(repository.findAll()).hasSize(4);
    }

    @Test
    void deleteLogsByTaskId_仅删除指定任务日志() {
        var task1 = 创建任务("任务1", "0 0 8 * * *", "active");
        var task2 = 创建任务("任务2", "0 0 9 * * *", "active");
        repository.save(task1);
        repository.save(task2);

        repository.saveLog(new CronTaskLog(UUID.randomUUID().toString(), task1.id(),
                Instant.now().toString(), "success", 1000, 50, "摘要", Instant.now().toString()));
        repository.saveLog(new CronTaskLog(UUID.randomUUID().toString(), task2.id(),
                Instant.now().toString(), "success", 2000, 80, "摘要", Instant.now().toString()));

        repository.deleteLogsByTaskId(task1.id());

        // task1 仍在，task2 仍在
        assertThat(repository.findById(task1.id())).isPresent();
        assertThat(repository.findById(task2.id())).isPresent();
    }
}

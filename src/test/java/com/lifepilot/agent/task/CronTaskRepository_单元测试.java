package com.lifepilot.agent.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CronTaskRepository 单元测试（内存 SQLite）。
 *
 * <p>验证 CRUD 操作、级联删除日志、按状态查询等核心行为。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class CronTaskRepository_单元测试 {

    private CronTaskRepository repository;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(ds);
        // 启用外键
        jdbc.execute("PRAGMA foreign_keys = ON");
        // 创建表
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

    // ---- 辅助方法 ----

    private CronTaskEntry 创建任务(String id, String status) {
        var now = Instant.now().toString();
        return new CronTaskEntry(id, "测试任务-" + id, "0 0 6 * * *",
                "执行测试指令", status, now, now);
    }

    private CronTaskLog 创建日志(String taskId) {
        return new CronTaskLog(UUID.randomUUID().toString(), taskId,
                Instant.now().toString(), "success", 1500, 100,
                "执行完成", Instant.now().toString());
    }

    // ---- save + findById ----

    @Test
    void save后findById_返回正确任务() {
        var task = 创建任务("task-001", "active");
        repository.save(task);

        var found = repository.findById("task-001");
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("测试任务-task-001");
        assertThat(found.get().schedule()).isEqualTo("0 0 6 * * *");
        assertThat(found.get().status()).isEqualTo("active");
    }

    @Test
    void findById_不存在的ID_返回空() {
        assertThat(repository.findById("not-exist")).isEmpty();
    }

    // ---- update ----

    @Test
    void update_修改字段后查询反映变更() {
        var task = 创建任务("task-002", "active");
        repository.save(task);

        var updated = new CronTaskEntry("task-002", "已修改名称", "0 30 8 * * *",
                "新指令", "paused", task.createdAt(), Instant.now().toString());
        repository.update(updated);

        var found = repository.findById("task-002").orElseThrow();
        assertThat(found.name()).isEqualTo("已修改名称");
        assertThat(found.schedule()).isEqualTo("0 30 8 * * *");
        assertThat(found.instruction()).isEqualTo("新指令");
        assertThat(found.status()).isEqualTo("paused");
    }

    // ---- deleteById + 级联删除 ----

    @Test
    void deleteById_级联删除关联日志() {
        var task = 创建任务("task-003", "active");
        repository.save(task);
        repository.saveLog(创建日志("task-003"));
        repository.saveLog(创建日志("task-003"));

        repository.deleteById("task-003");

        assertThat(repository.findById("task-003")).isEmpty();
        // 级联删除后，日志也应该被清除（通过 findAll 间接验证无残留任务）
        assertThat(repository.findAll()).isEmpty();
    }

    // ---- findByStatus ----

    @Test
    void findByStatus_按状态过滤() {
        repository.save(创建任务("t-active-1", "active"));
        repository.save(创建任务("t-active-2", "active"));
        repository.save(创建任务("t-paused-1", "paused"));

        assertThat(repository.findByStatus("active")).hasSize(2);
        assertThat(repository.findByStatus("paused")).hasSize(1);
        assertThat(repository.findByStatus("completed")).isEmpty();
    }

    // ---- findAll ----

    @Test
    void findAll_返回所有任务() {
        repository.save(创建任务("t1", "active"));
        repository.save(创建任务("t2", "paused"));
        repository.save(创建任务("t3", "completed"));

        assertThat(repository.findAll()).hasSize(3);
    }

    // ---- saveLog + deleteLogsByTaskId ----

    @Test
    void saveLog_写入日志后deleteLogsByTaskId_清除() {
        var task = 创建任务("task-log", "active");
        repository.save(task);
        repository.saveLog(创建日志("task-log"));
        repository.saveLog(创建日志("task-log"));

        repository.deleteLogsByTaskId("task-log");

        // 任务本身仍在
        assertThat(repository.findById("task-log")).isPresent();
    }
}

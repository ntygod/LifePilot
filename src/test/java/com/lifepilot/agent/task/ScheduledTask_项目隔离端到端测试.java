package com.lifepilot.agent.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 2 Task A7 —— 定时任务项目隔离端到端集成测试。
 *
 * <p>按 Plan 1 的 {@code ProjectLifecycle_集成测试} / {@code ProjectDeletion_级联集成测试}
 * 既定 pattern —— SingleConnectionDataSource + 内存 SQLite + 手动建表 + 手动构造 Repository，
 * 而不是 {@code @SpringBootTest}（后者会启动完整 Spring Context 触发 Flyway 校验
 * dev 数据库，非本测试目标；单连接 SQLite 下 Spring 事务在非 Spring 管理的 DataSource
 * 上意义有限，手动建表能精确控制 FK 约束语义）。</p>
 *
 * <p>对应验收点：
 * <ul>
 *   <li>按 projectId 过滤只返回归属该项目的任务（Task A2 读路径）</li>
 *   <li>findByProjectId(null) 返回主账户归属的任务（project_id IS NULL）</li>
 *   <li>主账户 vs 项目 vs 其他项目 三者互相隔离</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ScheduledTask_项目隔离端到端测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private CronTaskRepository cronRepo;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
        // 对照 V1 + V3 + V19 叠加后的最终 schema
        jdbcTemplate.execute("""
                CREATE TABLE cron_tasks (
                    id          TEXT PRIMARY KEY,
                    name        TEXT NOT NULL,
                    schedule    TEXT NOT NULL,
                    instruction TEXT NOT NULL,
                    status      TEXT NOT NULL DEFAULT 'active',
                    skill_ids   TEXT,
                    project_id  TEXT,
                    created_at  TEXT NOT NULL,
                    updated_at  TEXT NOT NULL
                )
                """);
        cronRepo = new CronTaskRepository(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private CronTaskEntry entry(String id, String projectId) {
        String now = Instant.now().toString();
        return new CronTaskEntry(id, "任务-" + id, "0 0 9 * * *", "指令",
                "active", now, now, null, projectId);
    }

    @Test
    void findByProjectId_只返回归属该项目的任务() {
        cronRepo.save(entry("t-project-1", "p-1"));
        cronRepo.save(entry("t-project-2", "p-1"));
        cronRepo.save(entry("t-main", null));
        cronRepo.save(entry("t-other-project", "p-2"));

        List<CronTaskEntry> projectTasks = cronRepo.findByProjectId("p-1");
        assertThat(projectTasks).hasSize(2);
        assertThat(projectTasks).allMatch(e -> "p-1".equals(e.projectId()));
    }

    @Test
    void findByProjectId_传null_返回主账户任务() {
        cronRepo.save(entry("t-project", "p-1"));
        cronRepo.save(entry("t-main-1", null));
        cronRepo.save(entry("t-main-2", null));

        List<CronTaskEntry> main = cronRepo.findByProjectId(null);
        assertThat(main).hasSize(2);
        assertThat(main).allMatch(e -> e.projectId() == null);
    }

    @Test
    void 隔离项目与主账户互不可见() {
        cronRepo.save(entry("t-p1", "p-1"));
        cronRepo.save(entry("t-p2", "p-2"));
        cronRepo.save(entry("t-main", null));

        // p-1 只看到自己的
        List<CronTaskEntry> p1Tasks = cronRepo.findByProjectId("p-1");
        assertThat(p1Tasks).hasSize(1);
        assertThat(p1Tasks.get(0).id()).isEqualTo("t-p1");

        // 主账户只看到 project_id IS NULL 的
        List<CronTaskEntry> mainTasks = cronRepo.findByProjectId(null);
        assertThat(mainTasks).hasSize(1);
        assertThat(mainTasks.get(0).id()).isEqualTo("t-main");

        // p-2 只看到自己的
        List<CronTaskEntry> p2Tasks = cronRepo.findByProjectId("p-2");
        assertThat(p2Tasks).hasSize(1);
        assertThat(p2Tasks.get(0).id()).isEqualTo("t-p2");
    }
}

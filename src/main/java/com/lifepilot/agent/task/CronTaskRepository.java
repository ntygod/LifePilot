package com.lifepilot.agent.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Cron 定时任务持久化仓库。
 *
 * <p>基于 JdbcTemplate 操作 SQLite 的 cron_tasks 和 cron_task_logs 表。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class CronTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(CronTaskRepository.class);

    private final JdbcTemplate jdbc;

    private static final RowMapper<CronTaskEntry> TASK_MAPPER = (rs, _) -> new CronTaskEntry(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("schedule"),
            rs.getString("instruction"),
            rs.getString("status"),
            rs.getString("created_at"),
            rs.getString("updated_at"),
            rs.getString("skill_ids"),
            rs.getString("project_id")
    );

    private static final RowMapper<CronTaskLog> LOG_MAPPER = (rs, _) -> new CronTaskLog(
            rs.getString("id"),
            rs.getString("task_id"),
            rs.getString("executed_at"),
            rs.getString("status"),
            rs.getLong("duration_ms"),
            rs.getInt("tokens_used"),
            rs.getString("summary"),
            rs.getString("created_at")
    );

    public CronTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 保存新任务。 */
    public void save(CronTaskEntry entry) {
        jdbc.update("""
                INSERT INTO cron_tasks (id, name, schedule, instruction, status, created_at, updated_at, skill_ids, project_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.id(), entry.name(), entry.schedule(), entry.instruction(),
                entry.status(), entry.createdAt(), entry.updatedAt(), entry.skillIds(), entry.projectId());
        log.debug("Cron 任务已保存: id={}, name={}, projectId={}",
                entry.id(), entry.name(), entry.projectId());
    }

    /** 更新任务。 */
    public void update(CronTaskEntry entry) {
        jdbc.update("""
                UPDATE cron_tasks SET name = ?, schedule = ?, instruction = ?,
                    status = ?, updated_at = ?, skill_ids = ?
                WHERE id = ?
                """,
                entry.name(), entry.schedule(), entry.instruction(),
                entry.status(), entry.updatedAt(), entry.skillIds(), entry.id());
        log.debug("Cron 任务已更新: id={}, name={}", entry.id(), entry.name());
    }

    /** 按 ID 删除任务（级联删除日志）。 */
    public void deleteById(String id) {
        jdbc.update("DELETE FROM cron_tasks WHERE id = ?", id);
        log.debug("Cron 任务已删除: id={}", id);
    }

    /** 按 ID 查询任务。 */
    public Optional<CronTaskEntry> findById(String id) {
        var results = jdbc.query("""
                SELECT id, name, schedule, instruction, status, created_at, updated_at, skill_ids, project_id
                FROM cron_tasks WHERE id = ?""", TASK_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** 按状态查询任务列表。 */
    public List<CronTaskEntry> findByStatus(String status) {
        return List.copyOf(jdbc.query(
                """
                SELECT id, name, schedule, instruction, status, created_at, updated_at, skill_ids, project_id
                FROM cron_tasks WHERE status = ? ORDER BY created_at""",
                TASK_MAPPER, status));
    }

    /** 查询所有任务。 */
    public List<CronTaskEntry> findAll() {
        return List.copyOf(jdbc.query(
                """
                SELECT id, name, schedule, instruction, status, created_at, updated_at, skill_ids, project_id
                FROM cron_tasks ORDER BY created_at""", TASK_MAPPER));
    }

    /**
     * 按项目归属查询任务列表。
     *
     * <p>{@code projectId == null} 表示归属主账户，走 {@code project_id IS NULL} 匹配；
     * 非 null 则按精确等值匹配。归属不可迁移，UPDATE 不会修改 project_id。</p>
     *
     * @param projectId 项目 ID；{@code null} 表示查询主账户任务
     */
    public List<CronTaskEntry> findByProjectId(@Nullable String projectId) {
        if (projectId == null) {
            return List.copyOf(jdbc.query(
                    """
                    SELECT id, name, schedule, instruction, status, created_at, updated_at, skill_ids, project_id
                    FROM cron_tasks WHERE project_id IS NULL ORDER BY created_at DESC""",
                    TASK_MAPPER));
        }
        return List.copyOf(jdbc.query(
                """
                SELECT id, name, schedule, instruction, status, created_at, updated_at, skill_ids, project_id
                FROM cron_tasks WHERE project_id = ? ORDER BY created_at DESC""",
                TASK_MAPPER, projectId));
    }

    /** 保存执行日志。 */
    public void saveLog(CronTaskLog logEntry) {
        jdbc.update("""
                INSERT INTO cron_task_logs (id, task_id, executed_at, status, duration_ms, tokens_used, summary, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                logEntry.id(), logEntry.taskId(), logEntry.executedAt(), logEntry.status(),
                logEntry.durationMs(), logEntry.tokensUsed(), logEntry.summary(), logEntry.createdAt());
    }

    /** 删除指定任务的所有执行日志。 */
    public void deleteLogsByTaskId(String taskId) {
        int count = jdbc.update("DELETE FROM cron_task_logs WHERE task_id = ?", taskId);
        log.debug("Cron 任务日志已删除: taskId={}, count={}", taskId, count);
    }
}

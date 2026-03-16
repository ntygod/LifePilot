package com.lifepilot.scheduler;

import com.lifepilot.scheduler.model.ScheduledTask;
import com.lifepilot.scheduler.model.TaskStatus;
import com.lifepilot.scheduler.model.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务仓储 — 基于 JdbcTemplate 操作 SQLite scheduled_tasks 表。
 *
 * <p>提供定时任务的 CRUD 操作，包括按状态查询、按 metadata 中的 scheduleId 查询等。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class ScheduledTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ScheduledTask> rowMapper;

    public ScheduledTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    /**
     * 保存定时任务。
     *
     * @param task 定时任务实体
     */
    public void save(ScheduledTask task) {
        jdbcTemplate.update("""
                INSERT INTO scheduled_tasks
                    (id, name, trigger_type, trigger_at, cron_expr, action_json,
                     status, error_message, last_triggered_at, next_trigger_at,
                     metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(), task.name(), task.triggerType().name(),
                task.triggerAt(), task.cronExpr(), task.actionJson(),
                task.status().name(), task.errorMessage(),
                task.lastTriggeredAt(), task.nextTriggerAt(),
                task.metadataJson(), task.createdAt(), task.updatedAt());

        log.info("定时任务保存成功: id={}, name={}", task.id(), task.name());
    }

    /**
     * 根据 ID 查找定时任务。
     *
     * @param id 任务 ID
     * @return 定时任务 Optional，不存在时返回 empty
     */
    public Optional<ScheduledTask> findById(String id) {
        List<ScheduledTask> results = jdbcTemplate.query(
                "SELECT * FROM scheduled_tasks WHERE id = ?", rowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 查询指定状态的所有定时任务。
     *
     * @param status 任务状态
     * @return 定时任务列表
     */
    public List<ScheduledTask> findAllByStatus(TaskStatus status) {
        return jdbcTemplate.query(
                "SELECT * FROM scheduled_tasks WHERE status = ?",
                rowMapper, status.name());
    }

    /**
     * 更新定时任务状态和错误信息。
     *
     * @param id           任务 ID
     * @param status       新状态
     * @param errorMessage 错误信息（可为 null）
     */
    public void updateStatus(String id, TaskStatus status, @Nullable String errorMessage) {
        String now = Instant.now().toString();
        int rows = jdbcTemplate.update("""
                UPDATE scheduled_tasks
                SET status = ?, error_message = ?, updated_at = ?
                WHERE id = ?
                """,
                status.name(), errorMessage, now, id);

        if (rows > 0) {
            log.info("定时任务状态更新: id={}, status={}", id, status);
        } else {
            log.warn("定时任务不存在，无法更新状态: id={}", id);
        }
    }

    /**
     * 更新定时任务的所有可变字段。
     *
     * @param task 包含更新后数据的定时任务实体
     */
    public void update(ScheduledTask task) {
        String now = Instant.now().toString();
        int rows = jdbcTemplate.update("""
                UPDATE scheduled_tasks
                SET name = ?, trigger_at = ?, cron_expr = ?, action_json = ?,
                    status = ?, error_message = ?, last_triggered_at = ?,
                    next_trigger_at = ?, metadata_json = ?, updated_at = ?
                WHERE id = ?
                """,
                task.name(), task.triggerAt(), task.cronExpr(), task.actionJson(),
                task.status().name(), task.errorMessage(),
                task.lastTriggeredAt(), task.nextTriggerAt(),
                task.metadataJson(), now, task.id());

        if (rows > 0) {
            log.info("定时任务更新成功: id={}", task.id());
        } else {
            log.warn("定时任务不存在，无法更新: id={}", task.id());
        }
    }

    /**
     * 通过 metadata 中的 scheduleId 查找关联的定时任务。
     *
     * <p>使用 LIKE 模式匹配在 metadata_json 文本中搜索 scheduleId。</p>
     *
     * @param scheduleId 日程 ID
     * @return 关联的定时任务 Optional，不存在时返回 empty
     */
    public Optional<ScheduledTask> findByMetadataScheduleId(String scheduleId) {
        String pattern = "%\"scheduleId\":\"" + scheduleId + "\"%";
        List<ScheduledTask> results = jdbcTemplate.query(
                "SELECT * FROM scheduled_tasks WHERE metadata_json LIKE ?",
                rowMapper, pattern);
        return results.stream().findFirst();
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 ScheduledTask record。 */
    private ScheduledTask mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ScheduledTask(
                rs.getString("id"),
                rs.getString("name"),
                TriggerType.valueOf(rs.getString("trigger_type")),
                rs.getString("trigger_at"),
                rs.getString("cron_expr"),
                rs.getString("action_json"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("error_message"),
                rs.getString("last_triggered_at"),
                rs.getString("next_trigger_at"),
                rs.getString("metadata_json"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }
}

package com.lifepilot.workflow.engine;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.model.WorkflowEvent;
import com.lifepilot.workflow.model.WorkflowEventType;

/**
 * 工作流审计事件记录器。
 *
 * <p>通过 JdbcTemplate 直接操作 workflow_events 表（追加写入），
 * 与 WorkflowRepository 职责分离。事件记录失败仅打 WARN 日志，不中断工作流执行。
 *
 * <p>当 {@code event-audit.enabled=false} 时，{@link #record} 方法为 no-op。
 *
 * @author zsg
 * @since 2026-03-09
 */
public class WorkflowEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventRecorder.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowConfigProperties config;

    public WorkflowEventRecorder(JdbcTemplate jdbcTemplate,
                                 WorkflowConfigProperties config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
    }

    /**
     * 记录审计事件。事件记录失败仅打 WARN 日志，不中断工作流执行。
     *
     * @param type       事件类型
     * @param instanceId 工作流实例 ID
     * @param workflowId 工作流定义 ID
     * @param stepId     步骤 ID（实例级事件为 null）
     * @param data       事件数据（可空）
     */
    public void record(WorkflowEventType type, String instanceId, String workflowId,
                       @Nullable String stepId, @Nullable Map<String, Object> data) {
        if (!config.getEventAudit().isEnabled()) {
            return;
        }
        try {
            String id = UUID.randomUUID().toString();
            String dataJson = toJson(data);
            String now = Instant.now().toString();

            jdbcTemplate.update(
                    """
                    INSERT INTO workflow_events (id, instance_id, workflow_id, type, step_id, data_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, instanceId, workflowId, type.name(), stepId, dataJson, now
            );
        } catch (Exception e) {
            log.warn("审计事件记录失败: type={}, instanceId={}, error={}",
                    type, instanceId, e.getMessage());
        }
    }

    /**
     * 查询实例的事件时间线（按 createdAt 升序）。
     *
     * @param instanceId 工作流实例 ID
     * @return 事件列表
     */
    public List<WorkflowEvent> getTimeline(String instanceId) {
        return jdbcTemplate.query(
                "SELECT * FROM workflow_events WHERE instance_id = ? ORDER BY created_at ASC",
                this::mapEvent,
                instanceId
        );
    }

    /**
     * 查询指定类型且在指定时间之后的事件。
     *
     * @param type  事件类型
     * @param since 起始时间
     * @return 事件列表
     */
    public List<WorkflowEvent> getEventsByType(WorkflowEventType type, Instant since) {
        return jdbcTemplate.query(
                "SELECT * FROM workflow_events WHERE type = ? AND created_at >= ? ORDER BY created_at ASC",
                this::mapEvent,
                type.name(), since.toString()
        );
    }

    /**
     * 清理过期事件，返回删除数量。
     *
     * @return 删除的事件数量
     */
    public int purgeExpiredEvents() {
        int retentionDays = config.getEventAudit().getRetentionDays();
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString();
        int deleted = jdbcTemplate.update(
                "DELETE FROM workflow_events WHERE created_at < ?", cutoff);
        if (deleted > 0) {
            log.info("清理过期审计事件: deleted={}, retentionDays={}", deleted, retentionDays);
        }
        return deleted;
    }

    // ==================== 内部方法 ====================

    private WorkflowEvent mapEvent(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new WorkflowEvent(
                rs.getString("id"),
                rs.getString("instance_id"),
                rs.getString("workflow_id"),
                WorkflowEventType.valueOf(rs.getString("type")),
                rs.getString("step_id"),
                rs.getString("data_json"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    @Nullable
    private String toJson(@Nullable Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(data);
        } catch (Exception e) {
            log.warn("事件数据序列化失败", e);
            return null;
        }
    }
}

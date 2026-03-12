package com.lifepilot.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.model.WorkflowEvent;
import com.lifepilot.workflow.model.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流审计事件持久化，并推送到实时订阅者。
 *
 * @author zsg
 * @since 2026-03-10
 */
public class WorkflowEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventRecorder.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowConfigProperties config;
    private final WorkflowRealtimeEventHub realtimeEventHub;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkflowEventRecorder(JdbcTemplate jdbcTemplate,
                                 WorkflowConfigProperties config,
                                 WorkflowRealtimeEventHub realtimeEventHub) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
        this.realtimeEventHub = realtimeEventHub;
    }

    public void record(WorkflowEventType type,
                       String instanceId,
                       String workflowId,
                       @Nullable String stepId,
                       @Nullable Map<String, Object> data) {
        if (!config.getEventAudit().isEnabled()) {
            return;
        }

        try {
            String id = UUID.randomUUID().toString();
            String dataJson = toJson(data);
            Instant createdAt = Instant.now();

            jdbcTemplate.update(
                    """
                    INSERT INTO workflow_events (id, instance_id, workflow_id, type, step_id, data_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    instanceId,
                    workflowId,
                    type.name(),
                    stepId,
                    dataJson,
                    createdAt.toString()
            );

            realtimeEventHub.publishEvent(new WorkflowEvent(
                    id,
                    instanceId,
                    workflowId,
                    type,
                    stepId,
                    dataJson,
                    createdAt
            ));
        } catch (Exception e) {
            log.warn("工作流事件记录失败: type={}, instanceId={}, error={}",
                    type, instanceId, e.getMessage());
        }
    }

    public List<WorkflowEvent> getTimeline(String instanceId) {
        return jdbcTemplate.query(
                "SELECT * FROM workflow_events WHERE instance_id = ? ORDER BY created_at ASC",
                this::mapEvent,
                instanceId
        );
    }

    public List<WorkflowEvent> getEventsByType(WorkflowEventType type, Instant since) {
        return jdbcTemplate.query(
                "SELECT * FROM workflow_events WHERE type = ? AND created_at >= ? ORDER BY created_at ASC",
                this::mapEvent,
                type.name(),
                since.toString()
        );
    }

    public int purgeExpiredEvents() {
        int retentionDays = config.getEventAudit().getRetentionDays();
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString();
        int deleted = jdbcTemplate.update("DELETE FROM workflow_events WHERE created_at < ?", cutoff);
        if (deleted > 0) {
            log.info("清理过期工作流事件: deleted={}, retentionDays={}", deleted, retentionDays);
        }
        return deleted;
    }

    private WorkflowEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
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
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("工作流事件数据序列化失败", e);
            return null;
        }
    }
}

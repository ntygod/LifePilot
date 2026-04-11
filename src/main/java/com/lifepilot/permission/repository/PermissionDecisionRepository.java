package com.lifepilot.permission.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionDecision;
import com.lifepilot.permission.model.PermissionDecisionEntry;
import com.lifepilot.permission.model.PermissionDecisionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.model.PermissionSubjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 权限判定审计仓储。
 *
 * @author zsg
 * @since 2026-03-25
 */
@Repository
public class PermissionDecisionRepository {

    private static final Logger log = LoggerFactory.getLogger(PermissionDecisionRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PermissionDecisionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public PermissionDecisionEntry save(PermissionRequest request, PermissionDecision decision) {
        ExecutionGrant matchedGrant = decision.matchedGrant();
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO permission_decisions (
                    id, session_id, trace_id, workspace_id, task_id, user_id,
                    tool_id, action_type, risk_level, channel, resource_scope_json,
                    decision_type, matched_grant_id, matched_subject_type, matched_subject_id,
                    reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                request.sessionId(),
                request.traceId(),
                request.workspaceId(),
                request.taskId(),
                request.userId(),
                request.toolId(),
                request.actionType().name(),
                request.riskLevel().name(),
                request.channel(),
                writeJson(request.resourceScope().values()),
                decision.type().name(),
                decision.matchedGrantId(),
                matchedGrant != null ? matchedGrant.subjectType().name() : null,
                matchedGrant != null ? matchedGrant.subjectId() : null,
                decision.reason(),
                now.toString()
        );
        log.debug("权限判定已记录: toolId={}, actionType={}, decisionType={}",
                request.toolId(), request.actionType(), decision.type());
        return new PermissionDecisionEntry(
                id,
                request.sessionId(),
                request.traceId(),
                request.workspaceId(),
                request.taskId(),
                request.userId(),
                request.toolId(),
                request.actionType(),
                request.riskLevel(),
                request.channel(),
                request.resourceScope(),
                decision.type(),
                decision.matchedGrantId(),
                matchedGrant != null ? matchedGrant.subjectType() : null,
                matchedGrant != null ? matchedGrant.subjectId() : null,
                decision.reason(),
                now
        );
    }

    public List<PermissionDecisionEntry> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                """
                SELECT id, session_id, trace_id, workspace_id, task_id, user_id, tool_id,
                       action_type, risk_level, channel, resource_scope_json, decision_type,
                       matched_grant_id, matched_subject_type, matched_subject_id, reason, created_at
                FROM permission_decisions WHERE session_id = ? ORDER BY created_at DESC
                """,
                this::mapRow,
                sessionId
        );
    }

    public List<PermissionDecisionEntry> findByTaskId(String taskId) {
        return jdbcTemplate.query(
                """
                SELECT id, session_id, trace_id, workspace_id, task_id, user_id, tool_id,
                       action_type, risk_level, channel, resource_scope_json, decision_type,
                       matched_grant_id, matched_subject_type, matched_subject_id, reason, created_at
                FROM permission_decisions WHERE task_id = ? ORDER BY created_at DESC
                """,
                this::mapRow,
                taskId
        );
    }

    private PermissionDecisionEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PermissionDecisionEntry(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("workspace_id"),
                rs.getString("task_id"),
                rs.getString("user_id"),
                rs.getString("tool_id"),
                PermissionActionType.valueOf(rs.getString("action_type")),
                RiskLevel.valueOf(rs.getString("risk_level")),
                rs.getString("channel"),
                ExecutionGrantScope.of(readMap(rs.getString("resource_scope_json"))),
                PermissionDecisionType.valueOf(rs.getString("decision_type")),
                rs.getString("matched_grant_id"),
                readSubjectType(rs.getString("matched_subject_type")),
                rs.getString("matched_subject_id"),
                rs.getString("reason"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    @Nullable
    private PermissionSubjectType readSubjectType(@Nullable String value) {
        return value == null || value.isBlank() ? null : PermissionSubjectType.valueOf(value);
    }

    private Map<String, Object> readMap(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("解析权限判定作用域失败: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化权限判定字段失败: " + e.getMessage(), e);
        }
    }
}

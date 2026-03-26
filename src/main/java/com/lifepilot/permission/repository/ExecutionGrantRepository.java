package com.lifepilot.permission.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
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
import java.util.Optional;

/**
 * 执行授权仓储。
 *
 * @author zsg
 * @since 2026-03-25
 */
@Repository
public class ExecutionGrantRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutionGrantRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExecutionGrantRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ExecutionGrant> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM execution_grants ORDER BY created_at DESC",
                this::mapRow
        );
    }

    public List<ExecutionGrant> findAllActive(Instant now) {
        return jdbcTemplate.query(
                """
                SELECT * FROM execution_grants
                WHERE revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY created_at DESC
                """,
                this::mapRow,
                now.toString()
        );
    }

    public List<ExecutionGrant> findActiveByActionType(PermissionActionType actionType, Instant now) {
        return findActiveByActionTypes(List.of(actionType), now);
    }

    public List<ExecutionGrant> findActiveByActionTypes(List<PermissionActionType> actionTypes, Instant now) {
        if (actionTypes == null || actionTypes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(actionTypes.size(), "?"));
        String sql = """
                SELECT * FROM execution_grants
                WHERE action_type IN (%s)
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY created_at DESC
                """.formatted(placeholders);
        Object[] params = new Object[actionTypes.size() + 1];
        for (int i = 0; i < actionTypes.size(); i++) {
            params[i] = actionTypes.get(i).name();
        }
        params[actionTypes.size()] = now.toString();
        return jdbcTemplate.query(sql, this::mapRow, params);
    }

    public List<ExecutionGrant> findBySubject(PermissionSubjectType subjectType, String subjectId) {
        return jdbcTemplate.query(
                """
                SELECT * FROM execution_grants
                WHERE subject_type = ? AND subject_id = ?
                ORDER BY created_at DESC
                """,
                this::mapRow,
                subjectType.name(),
                subjectId
        );
    }

    public Optional<ExecutionGrant> findById(String id) {
        return jdbcTemplate.query(
                "SELECT * FROM execution_grants WHERE id = ?",
                this::mapRow,
                id
        ).stream().findFirst();
    }

    public void save(ExecutionGrant grant) {
        jdbcTemplate.update(
                """
                INSERT INTO execution_grants (
                    id, subject_type, subject_id, action_type, risk_ceiling,
                    scope_json, channels_json, autonomous_allowed,
                    expires_at, revoked_at, revoked_by, revoked_reason,
                    created_by, source_entry_id, reason, metadata_json,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    subject_type = excluded.subject_type,
                    subject_id = excluded.subject_id,
                    action_type = excluded.action_type,
                    risk_ceiling = excluded.risk_ceiling,
                    scope_json = excluded.scope_json,
                    channels_json = excluded.channels_json,
                    autonomous_allowed = excluded.autonomous_allowed,
                    expires_at = excluded.expires_at,
                    revoked_at = excluded.revoked_at,
                    revoked_by = excluded.revoked_by,
                    revoked_reason = excluded.revoked_reason,
                    created_by = excluded.created_by,
                    source_entry_id = excluded.source_entry_id,
                    reason = excluded.reason,
                    metadata_json = excluded.metadata_json,
                    updated_at = excluded.updated_at
                """,
                grant.id(),
                grant.subjectType().name(),
                grant.subjectId(),
                grant.actionType().name(),
                grant.riskCeiling().name(),
                writeJson(grant.scope().values()),
                writeJson(grant.channels()),
                grant.autonomousAllowed() ? 1 : 0,
                formatInstant(grant.expiresAt()),
                formatInstant(grant.revokedAt()),
                grant.revokedBy(),
                grant.revokedReason(),
                grant.createdBy(),
                grant.sourceEntryId(),
                grant.reason(),
                writeJson(grant.metadata()),
                grant.createdAt().toString(),
                grant.updatedAt().toString()
        );
        log.debug("执行授权已保存: id={}, subjectType={}, subjectId={}, actionType={}",
                grant.id(), grant.subjectType(), grant.subjectId(), grant.actionType());
    }

    public int revoke(String id, @Nullable String revokedBy, @Nullable String revokedReason, Instant revokedAt) {
        int updated = jdbcTemplate.update(
                """
                UPDATE execution_grants
                SET revoked_at = ?, revoked_by = ?, revoked_reason = ?, updated_at = ?
                WHERE id = ? AND revoked_at IS NULL
                """,
                revokedAt.toString(),
                revokedBy,
                revokedReason,
                revokedAt.toString(),
                id
        );
        if (updated > 0) {
            log.debug("执行授权已撤销: id={}", id);
        }
        return updated;
    }

    private ExecutionGrant mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExecutionGrant(
                rs.getString("id"),
                PermissionSubjectType.valueOf(rs.getString("subject_type")),
                rs.getString("subject_id"),
                PermissionActionType.valueOf(rs.getString("action_type")),
                RiskLevel.valueOf(rs.getString("risk_ceiling")),
                ExecutionGrantScope.of(readMap(rs.getString("scope_json"))),
                readStringList(rs.getString("channels_json")),
                rs.getInt("autonomous_allowed") == 1,
                readInstant(rs.getString("expires_at")),
                readInstant(rs.getString("revoked_at")),
                rs.getString("revoked_by"),
                rs.getString("revoked_reason"),
                rs.getString("created_by"),
                rs.getString("source_entry_id"),
                rs.getString("reason"),
                readMap(rs.getString("metadata_json")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    @Nullable
    private Instant readInstant(@Nullable String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private List<String> readStringList(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : List.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("解析授权渠道列表失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> readMap(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("解析授权 JSON 字段失败: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化授权字段失败: " + e.getMessage(), e);
        }
    }

    @Nullable
    private String formatInstant(@Nullable Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}

package com.lifepilot.agent.initiative.execute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 执行授权持久化仓库 — 对接 initiative_permissions 表。
 *
 * @author zsg
 * @since 2026-06-01
 */
public class ExecutionPermissionRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPermissionRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public ExecutionPermissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(ExecutionPermission permission) {
        String toolsJson = permission.allowedTools() != null
                ? String.join(",", permission.allowedTools()) : null;
        jdbcTemplate.update("""
            INSERT INTO initiative_permissions (id, action_pattern, description, allowed_tools,
                max_risk, active, granted_at, revoked_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(action_pattern) DO UPDATE SET
                description = excluded.description,
                allowed_tools = excluded.allowed_tools,
                max_risk = excluded.max_risk,
                active = excluded.active,
                revoked_at = excluded.revoked_at
            """,
                permission.id(), permission.actionPattern(), permission.description(),
                toolsJson, permission.maxRisk().name(),
                permission.active() ? 1 : 0,
                permission.grantedAt().toString(),
                permission.revokedAt() != null ? permission.revokedAt().toString() : null
        );
    }

    public Optional<ExecutionPermission> findByActionPattern(String actionPattern) {
        var results = jdbcTemplate.query(
                "SELECT * FROM initiative_permissions WHERE action_pattern = ? AND active = 1",
                (rs, rowNum) -> mapRow(rs), actionPattern);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<ExecutionPermission> findAllActive() {
        return jdbcTemplate.query(
                "SELECT * FROM initiative_permissions WHERE active = 1",
                (rs, rowNum) -> mapRow(rs));
    }

    public void revoke(String actionPattern) {
        jdbcTemplate.update(
                "UPDATE initiative_permissions SET active = 0, revoked_at = ? WHERE action_pattern = ?",
                Instant.now().toString(), actionPattern);
    }

    private ExecutionPermission mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String toolsStr = rs.getString("allowed_tools");
        List<String> tools = toolsStr != null && !toolsStr.isBlank()
                ? List.of(toolsStr.split(",")) : List.of();
        String revokedAtStr = rs.getString("revoked_at");
        return new ExecutionPermission(
                rs.getString("id"),
                rs.getString("action_pattern"),
                rs.getString("description"),
                tools,
                ExecutionPermission.RiskLevel.valueOf(rs.getString("max_risk")),
                rs.getInt("active") == 1,
                Instant.parse(rs.getString("granted_at")),
                revokedAtStr != null ? Instant.parse(revokedAtStr) : null
        );
    }
}

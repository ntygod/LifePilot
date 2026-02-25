package com.lifepilot.interaction.middleware.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.lifepilot.interaction.model.TokenUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

/**
 * 审计事件持久化仓库，使用 JdbcTemplate 操作 gateway_audit_log 表。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class AuditEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 持久化审计事件。
     *
     * @param event 审计事件
     */
    public void save(AuditEvent event) {
        jdbcTemplate.update("""
            INSERT INTO gateway_audit_log
            (audit_id, message_id, session_id, channel_type, user_id,
             request_content_hash, request_summary, response_status_code,
             response_summary, route_type, latency_ms,
             prompt_tokens, completion_tokens, total_tokens, model_id,
             middleware_results_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            event.auditId(), event.messageId(), event.sessionId(),
            event.channelType(), event.userId(),
            event.requestContentHash(), event.requestSummary(),
            event.responseStatusCode(), event.responseSummary(),
            event.routeType(), event.latencyMs(),
            promptTokens(event), completionTokens(event), totalTokens(event), modelId(event),
            event.middlewareResultsJson(),
            event.createdAt().toString()
        );
    }

    /**
     * 按过滤条件查询审计事件。
     *
     * @param userId      用户 ID（可空）
     * @param channelType 通道类型（可空）
     * @param routeType   路由类型（可空）
     * @param from        起始时间（可空）
     * @param to          结束时间（可空）
     * @return 匹配的审计事件列表
     */
    public List<AuditEvent> findByFilters(@Nullable String userId, @Nullable String channelType,
                                           @Nullable String routeType,
                                           @Nullable Instant from, @Nullable Instant to) {
        var sql = new StringBuilder("SELECT * FROM gateway_audit_log WHERE 1=1");
        var params = new ArrayList<>();

        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (channelType != null) {
            sql.append(" AND channel_type = ?");
            params.add(channelType);
        }
        if (routeType != null) {
            sql.append(" AND route_type = ?");
            params.add(routeType);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            params.add(to.toString());
        }

        sql.append(" ORDER BY created_at DESC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> AuditEvent.builder()
                .auditId(rs.getString("audit_id"))
                .messageId(rs.getString("message_id"))
                .sessionId(rs.getString("session_id"))
                .channelType(rs.getString("channel_type"))
                .userId(rs.getString("user_id"))
                .requestContentHash(rs.getString("request_content_hash"))
                .requestSummary(rs.getString("request_summary"))
                .responseStatusCode(rs.getInt("response_status_code"))
                .responseSummary(rs.getString("response_summary"))
                .routeType(rs.getString("route_type"))
                .latencyMs(rs.getLong("latency_ms"))
                .tokenUsage(new TokenUsage(
                        rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"),
                        rs.getInt("total_tokens"),
                        rs.getString("model_id")))
                .middlewareResultsJson(rs.getString("middleware_results_json"))
                .createdAt(Instant.parse(rs.getString("created_at")))
                .build(),
            params.toArray());
    }

    private static int promptTokens(AuditEvent event) {
        return event.tokenUsage() != null ? event.tokenUsage().promptTokens() : 0;
    }

    private static int completionTokens(AuditEvent event) {
        return event.tokenUsage() != null ? event.tokenUsage().completionTokens() : 0;
    }

    private static int totalTokens(AuditEvent event) {
        return event.tokenUsage() != null ? event.tokenUsage().totalTokens() : 0;
    }

    private static String modelId(AuditEvent event) {
        return event.tokenUsage() != null ? event.tokenUsage().modelId() : null;
    }
}

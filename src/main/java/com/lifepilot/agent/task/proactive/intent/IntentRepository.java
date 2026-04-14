package com.lifepilot.agent.task.proactive.intent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 意图记忆仓储 — 持久化用户意图的生命周期数据。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class IntentRepository {

    private final JdbcTemplate jdbc;

    public IntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IntentRecord> ROW_MAPPER = (rs, _) -> new IntentRecord(
            rs.getString("id"),
            rs.getString("user_id"),
            com.lifepilot.agent.task.proactive.SafeEnum.parse(IntentType.class, rs.getString("intent_type"), IntentType.GOAL),
            rs.getString("goal"),
            rs.getString("trigger_condition"),
            rs.getString("source_session_id"),
            com.lifepilot.agent.task.proactive.SafeEnum.parse(IntentStatus.class, rs.getString("status"), IntentStatus.ACTIVE),
            rs.getInt("check_count"),
            Instant.parse(rs.getString("created_at")),
            rs.getString("expires_at") != null ? Instant.parse(rs.getString("expires_at")) : null,
            rs.getString("triggered_at") != null ? Instant.parse(rs.getString("triggered_at")) : null,
            rs.getString("fulfilled_at") != null ? Instant.parse(rs.getString("fulfilled_at")) : null,
            Instant.parse(rs.getString("updated_at")));

    /** 保存意图。 */
    public void save(IntentRecord r) {
        jdbc.update("""
                INSERT INTO proactive_intent_memory
                (id, user_id, intent_type, goal, trigger_condition, source_session_id,
                 status, check_count, created_at, expires_at, triggered_at, fulfilled_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.id(), r.userId(), r.intentType().name(), r.goal(), r.triggerCondition(),
                r.sourceSessionId(), r.status().name(), r.checkCount(),
                r.createdAt().toString(),
                r.expiresAt() != null ? r.expiresAt().toString() : null,
                r.triggeredAt() != null ? r.triggeredAt().toString() : null,
                r.fulfilledAt() != null ? r.fulfilledAt().toString() : null,
                r.updatedAt().toString());
    }

    /** 按 ID 查询。 */
    @Nullable
    public IntentRecord findById(String id) {
        var list = jdbc.query("SELECT id, user_id, intent_type, goal, trigger_condition, source_session_id, status, check_count, created_at, expires_at, triggered_at, fulfilled_at, updated_at FROM proactive_intent_memory WHERE id = ?", ROW_MAPPER, id);
        return list.isEmpty() ? null : list.getFirst();
    }

    /** 查询用户所有活跃意图。 */
    public List<IntentRecord> findActiveByUserId(String userId) {
        return jdbc.query("""
                SELECT id, user_id, intent_type, goal, trigger_condition, source_session_id,
                       status, check_count, created_at, expires_at, triggered_at, fulfilled_at, updated_at
                FROM proactive_intent_memory
                WHERE user_id = ? AND status = 'ACTIVE'
                ORDER BY created_at DESC
                """, ROW_MAPPER, userId);
    }

    /** 查询已过期但状态仍为 ACTIVE 的意图。 */
    public List<IntentRecord> findExpired(Instant now) {
        return jdbc.query("""
                SELECT id, user_id, intent_type, goal, trigger_condition, source_session_id,
                       status, check_count, created_at, expires_at, triggered_at, fulfilled_at, updated_at
                FROM proactive_intent_memory
                WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ?
                """, ROW_MAPPER, now.toString());
    }

    /** 更新意图状态。 */
    public void updateStatus(String id, IntentStatus status, Instant now) {
        String triggeredCol = status == IntentStatus.TRIGGERED ? now.toString() : null;
        String fulfilledCol = status == IntentStatus.FULFILLED ? now.toString() : null;
        jdbc.update("""
                UPDATE proactive_intent_memory
                SET status = ?, triggered_at = COALESCE(?, triggered_at),
                    fulfilled_at = COALESCE(?, fulfilled_at), updated_at = ?
                WHERE id = ?
                """, status.name(), triggeredCol, fulfilledCol, now.toString(), id);
    }

    /** 递增检查计数。 */
    public void incrementCheckCount(String id) {
        jdbc.update("""
                UPDATE proactive_intent_memory
                SET check_count = check_count + 1, updated_at = ?
                WHERE id = ?
                """, Instant.now().toString(), id);
    }
}

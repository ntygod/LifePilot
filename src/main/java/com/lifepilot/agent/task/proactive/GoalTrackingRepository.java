package com.lifepilot.agent.task.proactive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 目标追踪仓储 — 存储引擎对 L3 目标实体的追踪状态。
 *
 * <p>只记录引擎特有的 checkCount 和 lastFollowUpAt，
 * 不复制 L3 实体数据本身。</p>
 *
 * @author zsg
 * @since 2026-04-15
 */
public class GoalTrackingRepository {

    private final JdbcTemplate jdbc;

    public GoalTrackingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追踪记录。 */
    public record GoalTracking(
            String entityId,
            int checkCount,
            @Nullable Instant lastFollowUpAt,
            Instant updatedAt
    ) {}

    private static final RowMapper<GoalTracking> ROW_MAPPER = (rs, _) -> new GoalTracking(
            rs.getString("entity_id"),
            rs.getInt("check_count"),
            rs.getString("last_follow_up") != null ? Instant.parse(rs.getString("last_follow_up")) : null,
            Instant.parse(rs.getString("updated_at")));

    /** 按实体 ID 查询追踪记录。 */
    @Nullable
    public GoalTracking findByEntityId(String entityId) {
        var list = jdbc.query("""
                SELECT entity_id, check_count, last_follow_up, updated_at
                FROM proactive_goal_tracking WHERE entity_id = ?
                """, ROW_MAPPER, entityId);
        return list.isEmpty() ? null : list.getFirst();
    }

    /** 递增追问次数并更新时间（upsert 原子操作）。 */
    public void incrementCheckCount(String entityId) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO proactive_goal_tracking (entity_id, check_count, last_follow_up, updated_at)
                VALUES (?, 1, ?, ?)
                ON CONFLICT(entity_id) DO UPDATE SET
                    check_count = check_count + 1,
                    last_follow_up = excluded.last_follow_up,
                    updated_at = excluded.updated_at
                """, entityId, now.toString(), now.toString());
    }

    /** 清理孤立记录（对应 L3 实体已归档时调用）。 */
    public int deleteByEntityId(String entityId) {
        return jdbc.update("DELETE FROM proactive_goal_tracking WHERE entity_id = ?", entityId);
    }
}

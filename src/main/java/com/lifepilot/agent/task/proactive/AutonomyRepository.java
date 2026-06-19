package com.lifepilot.agent.task.proactive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 行为自主度仓储。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class AutonomyRepository {

    private final JdbcTemplate jdbc;

    public AutonomyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AutonomyConfig> ROW_MAPPER = (rs, _) -> new AutonomyConfig(
            rs.getString("user_id"),
            rs.getString("behavior_name"),
            AutonomyLevel.valueOf(Objects.requireNonNull(rs.getString("autonomy_level"),
                    "autonomy_level 不能为空")),
            rs.getInt("consecutive_positive"),
            rs.getInt("consecutive_negative"),
            rs.getInt("upgrade_suggested") == 1,
            rs.getString("cooldown_until") != null ? Instant.parse(rs.getString("cooldown_until")) : null,
            Instant.parse(rs.getString("updated_at")));

    /** 查询用户某行为的自主度配置。 */
    @Nullable
    public AutonomyConfig findByUserAndBehavior(String userId, String behaviorName) {
        var list = jdbc.query("""
                SELECT user_id, behavior_name, autonomy_level, consecutive_positive,
                       consecutive_negative, upgrade_suggested, cooldown_until, updated_at
                FROM proactive_behavior_autonomy
                WHERE user_id = ? AND behavior_name = ?
                """, ROW_MAPPER, userId, behaviorName);
        return list.isEmpty() ? null : list.getFirst();
    }

    /** 查询用户所有行为的自主度配置。 */
    public List<AutonomyConfig> findAllByUserId(String userId) {
        return jdbc.query("""
                SELECT user_id, behavior_name, autonomy_level, consecutive_positive,
                       consecutive_negative, upgrade_suggested, cooldown_until, updated_at
                FROM proactive_behavior_autonomy
                WHERE user_id = ? ORDER BY behavior_name
                """, ROW_MAPPER, userId);
    }

    /** 保存或更新自主度配置。 */
    public void upsert(AutonomyConfig config) {
        jdbc.update("""
                INSERT INTO proactive_behavior_autonomy
                    (user_id, behavior_name, autonomy_level, consecutive_positive, consecutive_negative,
                     upgrade_suggested, cooldown_until, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, behavior_name) DO UPDATE SET
                    autonomy_level = excluded.autonomy_level,
                    consecutive_positive = excluded.consecutive_positive,
                    consecutive_negative = excluded.consecutive_negative,
                    upgrade_suggested = excluded.upgrade_suggested,
                    cooldown_until = excluded.cooldown_until,
                    updated_at = excluded.updated_at
                """,
                config.userId(), config.behaviorName(), config.autonomyLevel().name(),
                config.consecutivePositive(), config.consecutiveNegative(),
                config.upgradeSuggested() ? 1 : 0,
                config.cooldownUntil() != null ? config.cooldownUntil().toString() : null,
                config.updatedAt().toString());
    }
}

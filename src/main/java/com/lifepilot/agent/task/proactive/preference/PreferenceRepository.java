package com.lifepilot.agent.task.proactive.preference;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

/**
 * 偏好模型仓储。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class PreferenceRepository {

    private final JdbcTemplate jdbc;

    public PreferenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<PreferenceEntry> ROW_MAPPER = (rs, _) -> new PreferenceEntry(
            rs.getString("user_id"),
            PreferenceDimension.valueOf(rs.getString("dimension")),
            rs.getString("preference_key"),
            rs.getFloat("preference_value"),
            rs.getInt("observation_count"),
            rs.getString("last_observed_at") != null ? Instant.parse(rs.getString("last_observed_at")) : null,
            Instant.parse(rs.getString("updated_at")));

    /** 查询用户某维度的所有偏好。 */
    public List<PreferenceEntry> findByDimension(String userId, PreferenceDimension dimension) {
        return jdbc.query("""
                SELECT * FROM proactive_user_preferences
                WHERE user_id = ? AND dimension = ?
                ORDER BY preference_value DESC
                """, ROW_MAPPER, userId, dimension.name());
    }

    /** 查询用户所有偏好。 */
    public List<PreferenceEntry> findAllByUserId(String userId) {
        return jdbc.query("""
                SELECT * FROM proactive_user_preferences
                WHERE user_id = ? ORDER BY dimension, preference_value DESC
                """, ROW_MAPPER, userId);
    }

    /** 观察并更新偏好值（加权移动平均）。 */
    public void observe(String userId, PreferenceDimension dimension, String key, float observed) {
        Instant now = Instant.now();
        observed = Math.max(0f, Math.min(1f, observed));
        jdbc.update("""
                INSERT INTO proactive_user_preferences
                    (user_id, dimension, preference_key, preference_value, observation_count, last_observed_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (user_id, dimension, preference_key) DO UPDATE SET
                    preference_value = (proactive_user_preferences.preference_value * proactive_user_preferences.observation_count + ?)
                                       / (proactive_user_preferences.observation_count + 1),
                    observation_count = proactive_user_preferences.observation_count + 1,
                    last_observed_at = ?,
                    updated_at = ?
                """, userId, dimension.name(), key, observed, now.toString(), now.toString(),
                observed, now.toString(), now.toString());
    }
}

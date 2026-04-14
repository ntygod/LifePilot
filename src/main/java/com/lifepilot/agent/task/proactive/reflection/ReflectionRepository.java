package com.lifepilot.agent.task.proactive.reflection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 反思经验仓储。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ReflectionRepository {

    private final JdbcTemplate jdbc;

    public ReflectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<ReflectionExperience> ROW_MAPPER = (rs, _) -> new ReflectionExperience(
            rs.getString("id"), rs.getString("user_id"), rs.getString("week_start"),
            rs.getString("useful_patterns"), rs.getString("missed_opportunities"),
            rs.getString("strategy_adjustments"), rs.getString("raw_reflection"),
            Instant.parse(rs.getString("created_at")));

    public void save(ReflectionExperience exp) {
        jdbc.update("""
                INSERT INTO proactive_reflection_experiences
                (id, user_id, week_start, useful_patterns, missed_opportunities,
                 strategy_adjustments, raw_reflection, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, exp.id(), exp.userId(), exp.weekStart(), exp.usefulPatterns(),
                exp.missedOpportunities(), exp.strategyAdjustments(),
                exp.rawReflection(), exp.createdAt().toString());
    }

    /** 查最近一次反思经验。 */
    @Nullable
    public ReflectionExperience findLatestByUserId(String userId) {
        var list = jdbc.query("""
                SELECT id, user_id, week_start, useful_patterns, missed_opportunities,
                       strategy_adjustments, raw_reflection, created_at
                FROM proactive_reflection_experiences
                WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
                """, ROW_MAPPER, userId);
        return list.isEmpty() ? null : list.getFirst();
    }

    /** 查最近 N 条经验。 */
    public List<ReflectionExperience> findRecentByUserId(String userId, int limit) {
        return jdbc.query("""
                SELECT id, user_id, week_start, useful_patterns, missed_opportunities,
                       strategy_adjustments, raw_reflection, created_at
                FROM proactive_reflection_experiences
                WHERE user_id = ? ORDER BY created_at DESC LIMIT ?
                """, ROW_MAPPER, userId, limit);
    }
}

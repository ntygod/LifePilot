package com.lifepilot.agent.task.proactive.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 用户画像仓储。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class UserProfileRepository {

    private final JdbcTemplate jdbc;

    public UserProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<UserProfile> ROW_MAPPER = (rs, _) -> new UserProfile(
            rs.getString("user_id"),
            rs.getString("work_rhythm"),
            rs.getString("preferences"),
            rs.getString("goals"),
            rs.getString("full_portrait"),
            rs.getInt("conversation_count"),
            rs.getString("last_consolidated_at") != null ? Instant.parse(rs.getString("last_consolidated_at")) : null,
            Instant.parse(rs.getString("updated_at")));

    @Nullable
    public UserProfile findByUserId(String userId) {
        var list = jdbc.query("""
                SELECT user_id, work_rhythm, preferences, goals, full_portrait,
                       conversation_count, last_consolidated_at, updated_at
                FROM proactive_user_profiles WHERE user_id = ?
                """, ROW_MAPPER, userId);
        return list.isEmpty() ? null : list.getFirst();
    }

    public void upsert(UserProfile profile) {
        jdbc.update("""
                INSERT INTO proactive_user_profiles
                    (user_id, work_rhythm, preferences, goals, full_portrait,
                     conversation_count, last_consolidated_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    work_rhythm = excluded.work_rhythm,
                    preferences = excluded.preferences,
                    goals = excluded.goals,
                    full_portrait = excluded.full_portrait,
                    conversation_count = excluded.conversation_count,
                    last_consolidated_at = excluded.last_consolidated_at,
                    updated_at = excluded.updated_at
                """,
                profile.userId(), profile.workRhythm(), profile.preferences(),
                profile.goals(), profile.fullPortrait(), profile.conversationCount(),
                profile.lastConsolidatedAt() != null ? profile.lastConsolidatedAt().toString() : null,
                profile.updatedAt().toString());
    }

    public void incrementConversationCount(String userId) {
        jdbc.update("""
                UPDATE proactive_user_profiles
                SET conversation_count = conversation_count + 1, updated_at = ?
                WHERE user_id = ?
                """, Instant.now().toString(), userId);
    }
}

package com.lifepilot.agent.task.proactive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

/**
 * 排队动作仓储 — 存取 QUEUE 级别的主动行为动作。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class QueuedActionRepository {

    private final JdbcTemplate jdbc;

    public QueuedActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<QueuedActionRecord> ROW_MAPPER = (rs, _) ->
            new QueuedActionRecord(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("behavior"),
                    rs.getString("topic_key"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getFloat("score"),
                    rs.getString("metadata"),
                    rs.getInt("shown") == 1,
                    Instant.parse(rs.getString("created_at")),
                    rs.getString("shown_at") != null ? Instant.parse(rs.getString("shown_at")) : null
            );

    /** 保存排队动作（幂等 — 重复 ID 覆盖更新）。 */
    public void save(QueuedActionRecord record) {
        jdbc.update("""
                INSERT INTO proactive_queued_actions
                    (id, user_id, behavior, topic_key, title, content, score, metadata, shown, created_at, shown_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title, content = excluded.content,
                    score = excluded.score, metadata = excluded.metadata
                """,
                record.id(), record.userId(), record.behavior(),
                record.topicKey(), record.title(), record.content(),
                record.score(), record.metadata(),
                record.shown() ? 1 : 0,
                record.createdAt().toString(),
                record.shownAt() != null ? record.shownAt().toString() : null);
    }

    /** 查询用户未展示的排队动作（按分数降序）。 */
    public List<QueuedActionRecord> findPendingByUserId(String userId, int limit) {
        return jdbc.query("""
                SELECT id, user_id, behavior, topic_key, title, content, score, metadata,
                       shown, created_at, shown_at
                FROM proactive_queued_actions
                WHERE user_id = ? AND shown = 0
                ORDER BY score DESC, created_at DESC
                LIMIT ?
                """, ROW_MAPPER, userId, limit);
    }

    /** 标记为已展示。 */
    public void markShown(String id) {
        jdbc.update("""
                UPDATE proactive_queued_actions
                SET shown = 1, shown_at = ?
                WHERE id = ?
                """, Instant.now().toString(), id);
    }

    /** 清理过期排队动作（超过指定时间的记录）。 */
    public int deleteOlderThan(Instant before) {
        return jdbc.update("""
                DELETE FROM proactive_queued_actions
                WHERE created_at < ?
                """, before.toString());
    }
}

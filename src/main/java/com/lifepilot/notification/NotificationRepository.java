package com.lifepilot.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 通知数据访问层，操作 {@code notification_history} 表。
 *
 * @author zsg
 * @since 2026-03-13
 */
public class NotificationRepository {

    private static final Logger log = LoggerFactory.getLogger(NotificationRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<NotificationRecord> notificationRowMapper = (rs, rowNum) -> new NotificationRecord(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("type_id"),
            rs.getString("content_json"),
            rs.getString("channel"),
            rs.getString("read_status"),
            rs.getString("status"),
            rs.getString("metadata_json"),
            Instant.parse(rs.getString("sent_at")),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    /**
     * 保存通知历史记录。
     *
     * @param record 通知记录
     */
    public void save(NotificationRecord record) {
        jdbcTemplate.update("""
                INSERT INTO notification_history (id, user_id, type_id, content_json, channel,
                    read_status, status, metadata_json, sent_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(), record.userId(), record.typeId(),
                record.contentJson(), record.channel(),
                record.readStatus(), record.status(), record.metadataJson(),
                record.sentAt().toString(), record.createdAt().toString(), record.updatedAt().toString());
    }

    /**
     * 根据 ID 查询通知记录。
     *
     * @param id 通知 ID
     * @return 通知记录（可能为空）
     */
    public Optional<NotificationRecord> findById(String id) {
        var list = jdbcTemplate.query(
                "SELECT * FROM notification_history WHERE id = ?",
                notificationRowMapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /**
     * 按用户 ID 分页查询通知历史，按 sent_at 降序。
     *
     * @param userId 用户 ID
     * @param page   页码（从 0 开始）
     * @param size   每页大小
     * @return 通知记录列表
     */
    public List<NotificationRecord> findByUserId(String userId, int page, int size) {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM notification_history WHERE user_id = ? ORDER BY sent_at DESC LIMIT ? OFFSET ?",
                notificationRowMapper, userId, size, page * size));
    }

    /**
     * 查询指定用户在某个类型下的最近通知历史。
     *
     * @param userId 用户 ID
     * @param typeId 通知类型
     * @param since  起始时间
     * @param limit  最大返回条数
     * @return 通知记录列表
     */
    public List<NotificationRecord> findByUserIdAndTypeSince(String userId, String typeId, Instant since, int limit) {
        return List.copyOf(jdbcTemplate.query(
                """
                SELECT * FROM notification_history
                WHERE user_id = ? AND type_id = ? AND sent_at >= ?
                ORDER BY sent_at DESC
                LIMIT ?
                """,
                notificationRowMapper,
                userId, typeId, since.toString(), limit
        ));
    }

    /**
     * 统计用户通知总数。
     *
     * @param userId 用户 ID
     * @return 通知总数
     */
    public long countByUserId(String userId) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_history WHERE user_id = ?",
                Long.class, userId);
        return count != null ? count : 0L;
    }

    /**
     * 统计用户未读通知数量。
     *
     * @param userId 用户 ID
     * @return 未读通知数量
     */
    public long countUnreadByUserId(String userId) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_history WHERE user_id = ? AND read_status = 'UNREAD'",
                Long.class, userId);
        return count != null ? count : 0L;
    }

    /**
     * 统计某类通知在指定时间之后的发送数量。
     *
     * @param userId 用户 ID
     * @param typeId 通知类型
     * @param since  起始时间
     * @return 已发送数量
     */
    public long countSentByUserIdAndTypeSince(String userId, String typeId, Instant since) {
        var count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM notification_history
                WHERE user_id = ? AND type_id = ? AND status = 'SENT' AND sent_at >= ?
                """,
                Long.class, userId, typeId, since.toString());
        return count != null ? count : 0L;
    }

    /**
     * 标记单条通知为已读。
     *
     * @param id 通知 ID
     */
    public void markAsRead(String id) {
        jdbcTemplate.update(
                "UPDATE notification_history SET read_status = 'READ', updated_at = ? WHERE id = ?",
                Instant.now().toString(), id);
    }

    /**
     * 标记用户所有未读通知为已读。
     *
     * @param userId 用户 ID
     * @return 更新的记录数
     */
    public int markAllAsRead(String userId) {
        return jdbcTemplate.update(
                "UPDATE notification_history SET read_status = 'READ', updated_at = ? WHERE user_id = ? AND read_status = 'UNREAD'",
                Instant.now().toString(), userId);
    }
}

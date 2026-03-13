package com.lifepilot.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 通知数据访问层，操作 notification_history、passive_notification_queue、notification_settings 表。
 *
 * @author zsg
 * @since 2026-03-13
 */
public class NotificationRepository {

    private static final Logger log = LoggerFactory.getLogger(NotificationRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== notification_history ====================

    private final RowMapper<NotificationRecord> notificationRowMapper = (rs, rowNum) -> new NotificationRecord(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("type_id"),
            Urgency.valueOf(rs.getString("urgency")),
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
                INSERT INTO notification_history (id, user_id, type_id, urgency, content_json, channel,
                    read_status, status, metadata_json, sent_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(), record.userId(), record.typeId(),
                record.urgency().name(), record.contentJson(), record.channel(),
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
     * 按用户 ID 和紧急程度分页查询通知历史，按 sent_at 降序。
     *
     * @param userId  用户 ID
     * @param urgency 紧急程度
     * @param page    页码（从 0 开始）
     * @param size    每页大小
     * @return 通知记录列表
     */
    public List<NotificationRecord> findByUserIdAndUrgency(String userId, String urgency, int page, int size) {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM notification_history WHERE user_id = ? AND urgency = ? ORDER BY sent_at DESC LIMIT ? OFFSET ?",
                notificationRowMapper, userId, urgency, size, page * size));
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
     * 统计用户指定紧急程度的通知总数。
     *
     * @param userId  用户 ID
     * @param urgency 紧急程度
     * @return 通知总数
     */
    public long countByUserIdAndUrgency(String userId, String urgency) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_history WHERE user_id = ? AND urgency = ?",
                Long.class, userId, urgency);
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

    // ==================== notification_settings ====================

    private final RowMapper<NotificationSettingRecord> settingRowMapper = (rs, rowNum) -> {
        List<String> channels;
        try {
            channels = MAPPER.readValue(rs.getString("channels_json"), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("通知设置 channels_json 解析失败: id={}", rs.getString("id"), e);
            channels = List.of();
        }
        return new NotificationSettingRecord(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("type_id"),
                rs.getInt("enabled") == 1,
                channels,
                Urgency.valueOf(rs.getString("min_urgency")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    };

    /**
     * 查询用户指定类型的通知设置。
     *
     * @param userId 用户 ID
     * @param typeId 通知类型 ID
     * @return 通知设置（可能为空）
     */
    public Optional<NotificationSettingRecord> findSettingByUserIdAndTypeId(String userId, String typeId) {
        var list = jdbcTemplate.query(
                "SELECT * FROM notification_settings WHERE user_id = ? AND type_id = ?",
                settingRowMapper, userId, typeId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /**
     * 查询用户所有通知设置。
     *
     * @param userId 用户 ID
     * @return 通知设置列表
     */
    public List<NotificationSettingRecord> findSettingsByUserId(String userId) {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM notification_settings WHERE user_id = ?",
                settingRowMapper, userId));
    }

    /**
     * 保存或更新通知设置（UPSERT）。
     *
     * @param setting 通知设置
     */
    public void saveSetting(NotificationSettingRecord setting) {
        String channelsJson;
        try {
            channelsJson = MAPPER.writeValueAsString(setting.channels());
        } catch (JsonProcessingException e) {
            log.warn("通知设置 channels 序列化失败: userId={}, typeId={}", setting.userId(), setting.typeId(), e);
            channelsJson = "[]";
        }
        jdbcTemplate.update("""
                INSERT INTO notification_settings (id, user_id, type_id, enabled, channels_json, min_urgency, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id, type_id) DO UPDATE SET
                    enabled = excluded.enabled,
                    channels_json = excluded.channels_json,
                    min_urgency = excluded.min_urgency,
                    updated_at = excluded.updated_at
                """,
                setting.id(), setting.userId(), setting.typeId(),
                setting.enabled() ? 1 : 0, channelsJson, setting.minUrgency().name(),
                setting.createdAt().toString(), setting.updatedAt().toString());
    }

    // ==================== passive_notification_queue ====================

    private final RowMapper<PassiveQueueEntry> queueEntryRowMapper = (rs, rowNum) -> new PassiveQueueEntry(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("type_id"),
            Urgency.valueOf(rs.getString("urgency")),
            rs.getString("content_json"),
            rs.getInt("delivered") == 1,
            Instant.parse(rs.getString("enqueued_at"))
    );

    /**
     * 保存被动队列条目。
     *
     * @param entry 队列条目
     */
    public void saveQueueEntry(PassiveQueueEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO passive_notification_queue (id, user_id, type_id, urgency, content_json, delivered, enqueued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                entry.id(), entry.userId(), entry.typeId(),
                entry.urgency().name(), entry.contentJson(),
                entry.delivered() ? 1 : 0, entry.enqueuedAt().toString());
    }

    /**
     * 查询所有未投递的队列条目。
     *
     * @return 未投递条目列表
     */
    public List<PassiveQueueEntry> findUndeliveredEntries() {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM passive_notification_queue WHERE delivered = 0 ORDER BY enqueued_at ASC",
                queueEntryRowMapper));
    }

    /**
     * 批量标记队列条目为已投递。
     *
     * @param ids 条目 ID 列表
     */
    public void markEntriesDelivered(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        var placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        jdbcTemplate.update(
                "UPDATE passive_notification_queue SET delivered = 1 WHERE id IN (" + placeholders + ")",
                ids.toArray());
    }
}
